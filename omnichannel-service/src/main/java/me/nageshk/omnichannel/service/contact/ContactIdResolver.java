package me.nageshk.omnichannel.service.contact;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import me.nageshk.omnichannel.common.api.NoDeviceException;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.config.OmnichannelProperties;
import me.nageshk.omnichannel.common.rpc.RpcException;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactRepository;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.phone.PhoneNumber;
import me.nageshk.omnichannel.db.phone.PhoneNumberRepository;
import me.nageshk.omnichannel.service.inbox.InboxDevices;
import me.nageshk.omnichannel.service.inbox.InboxIngestService;
import me.nageshk.omnichannel.service.rpc.RpcDispatcher;
import me.nageshk.omnichannel.service.ws.DeviceSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Finds a person's other WhatsApp id (spec 4.3): a contact known only by its hidden {@code @lid} id, or only by its
 * phone, is looked up with the page's {@code resolveIds} on the device of a number it chats with. Each id the device
 * answers that the contact lacks is added when nobody holds it, merges the two contacts when another holds it, and is
 * left alone when a merge under way holds it. Read-only toward WhatsApp.
 *
 * <p>Everything runs on {@code contactResolverExecutor} (one thread), never on the ingest thread: at most one attempt
 * per contact per {@code resolveRetry} after a message, at most one device call per {@code resolvePace} per device.
 * Both are kept in memory and start over on a restart.
 */
@Service
public class ContactIdResolver {

    static final String METHOD = "resolveIds";

    private static final Logger log = LoggerFactory.getLogger(ContactIdResolver.class);
    private static final String PHONE_SUFFIX = "@c.us";
    private static final String LID_SUFFIX = "@lid";
    /** Above this many remembered attempts, those whose retry is due (and so mean nothing) are dropped. */
    private static final int PRUNE_AT = 10_000;

    private final ContactRepository contacts;
    private final ContactMergeService merges;
    private final PhoneNumberRepository numbers;
    private final InboxDevices devices;
    private final RpcDispatcher dispatcher;
    private final MongoTemplate mongo;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Executor executor;
    private final Duration retry;
    private final Duration pace;

    /** When each contact (by id) was last looked up, whatever came of it. */
    private final ConcurrentHashMap<String, Instant> lastAttempt = new ConcurrentHashMap<>();
    /** When each number's device was last asked ({@link System#nanoTime()}); only the resolver thread uses it. */
    private final Map<String, Long> lastCall = new ConcurrentHashMap<>();
    /** Numbers whose extension does not know {@code resolveIds} yet, already logged once. */
    private final Set<String> outdated = ConcurrentHashMap.newKeySet();

    public ContactIdResolver(ContactRepository contacts, ContactMergeService merges, PhoneNumberRepository numbers,
            InboxDevices devices, RpcDispatcher dispatcher, MongoTemplate mongo, ObjectMapper mapper, Clock clock,
            @Qualifier("contactResolverExecutor") Executor executor, OmnichannelProperties props) {
        this.contacts = contacts;
        this.merges = merges;
        this.numbers = numbers;
        this.devices = devices;
        this.dispatcher = dispatcher;
        this.mongo = mongo;
        this.mapper = mapper;
        this.clock = clock;
        this.executor = executor;
        this.retry = props.contacts().resolveRetry();
        this.pace = props.contacts().resolvePace();
    }

    /**
     * Queues a lookup of the contact's other id on the number's device, whenever it was last tried, and counts it as
     * an attempt. The future completes when the lookup is done; a missing device, a failed call or an answer that
     * changes nothing complete it normally (each failure is logged once, here).
     */
    public CompletableFuture<Void> resolve(String workspaceId, String phoneNumberId, String contactId) {
        lastAttempt.put(contactId, clock.instant());
        return CompletableFuture.runAsync(() -> guarded(contactId, () -> lookUp(workspaceId, phoneNumberId, contactId)),
                executor);
    }

    /**
     * After an inbound message is stored: returns at once; on the resolver's thread, looks the contact up when it
     * lacks a phone or a hidden id, the number's device is ready and its last attempt is {@code resolveRetry} old.
     * Otherwise nothing happens and a later message tries again.
     */
    void afterMessage(String workspaceId, String phoneNumberId, String contactId) {
        executor.execute(() -> guarded(contactId, () -> {
            Contact c = contacts.findByIdAndWorkspaceId(contactId, workspaceId).orElse(null);
            if (c == null || !unresolved(c) || !devices.isReady(workspaceId, phoneNumberId) || !claim(contactId)) {
                return;
            }
            lookUp(workspaceId, phoneNumberId, contactId);
        }));
    }

    /**
     * Queues every contact of the brand that lacks a phone or a hidden id and chats on a number whose device is ready
     * now (on one such number), and finishes every merge left half done ({@code mergingInto} set). Returns how many
     * were queued.
     */
    public int queueBrand(String workspaceId) {
        int queued = 0;
        try (Stream<Contact> merging = contacts.streamMergingByWorkspaceId(workspaceId)) {
            for (Contact m : (Iterable<Contact>) merging::iterator) {
                String survivorId = m.getMergingInto();
                String otherId = m.getId();
                executor.execute(() -> guarded(otherId, () -> merge(workspaceId, survivorId, otherId)));
                queued++;
            }
        }
        Map<String, String> readyNumberOf = readyNumberByContact(workspaceId);
        if (readyNumberOf.isEmpty()) return queued;
        try (Stream<Contact> unresolved = contacts.streamUnresolvedByWorkspaceId(workspaceId)) {
            for (Contact c : (Iterable<Contact>) unresolved::iterator) {
                String phoneNumberId = readyNumberOf.get(c.getId());
                if (phoneNumberId == null || askWith(c) == null) continue;
                resolve(workspaceId, phoneNumberId, c.getId());
                queued++;
            }
        }
        return queued;
    }

    /** Whether the contact lacks a phone or a hidden id (a contact being merged away is neither). */
    static boolean unresolved(Contact c) {
        return c.getMergingInto() == null
                && (c.getPhone() == null || c.getWaIds().stream().noneMatch(id -> id.endsWith(LID_SUFFIX)));
    }

    /** The id to ask with: a hidden one when the contact has no phone, else a phone id; null when it has neither. */
    static String askWith(Contact c) {
        String lid = c.getWaIds().stream().filter(id -> id.endsWith(LID_SUFFIX)).findFirst().orElse(null);
        String phoneId = c.getWaIds().stream().filter(id -> id.endsWith(PHONE_SUFFIX)).findFirst()
                .orElse(c.getPhone() != null ? c.getPhone() + PHONE_SUFFIX : null);
        if (c.getPhone() == null && lid != null) return lid;
        return phoneId != null ? phoneId : lid;
    }

    /** The well-formed ids of a {@code resolveIds} answer, phone first. */
    static List<String> counterparts(JsonNode answer) {
        List<String> ids = new ArrayList<>();
        String phone = text(answer, "phone");
        if (phone != null && phone.endsWith(PHONE_SUFFIX) && Phones.isValid(digits(phone))) ids.add(phone);
        String lid = text(answer, "lid");
        if (lid != null && lid.endsWith(LID_SUFFIX) && lid.length() > LID_SUFFIX.length()) ids.add(lid);
        return ids;
    }

    /** True, and the attempt recorded, when the contact was not tried within {@code resolveRetry}. */
    private boolean claim(String contactId) {
        Instant now = clock.instant();
        boolean[] due = {false};
        lastAttempt.compute(contactId, (id, last) -> {
            if (last != null && now.isBefore(last.plus(retry))) return last;
            due[0] = true;
            return now;
        });
        if (lastAttempt.size() > PRUNE_AT) lastAttempt.values().removeIf(at -> !now.isBefore(at.plus(retry)));
        return due[0];
    }

    private void lookUp(String workspaceId, String phoneNumberId, String contactId) {
        Contact c = contacts.findByIdAndWorkspaceId(contactId, workspaceId).orElse(null);
        if (c == null || !unresolved(c)) return;
        String known = askWith(c);
        if (known == null) return;
        DeviceSession device;
        try {
            device = devices.requireReady(workspaceId, phoneNumberId);
        } catch (NoDeviceException | NotFoundException e) {
            log.debug("contact {} not looked up: {}", contactId, e.getMessage());
            return;
        }
        JsonNode answer = ask(device, phoneNumberId, known, contactId);
        if (answer != null) apply(workspaceId, contactId, counterparts(answer));
    }

    /** The device's answer, or null when the call failed (logged) or the thread is stopping. */
    private JsonNode ask(DeviceSession device, String phoneNumberId, String id, String contactId) {
        try {
            Long last = lastCall.get(phoneNumberId);
            long wait = last == null ? 0 : last + pace.toNanos() - System.nanoTime();
            if (wait > 0) TimeUnit.NANOSECONDS.sleep(wait);
            lastCall.put(phoneNumberId, System.nanoTime());
            JsonNode answer = dispatcher.call(device, METHOD, mapper.createObjectNode().put("id", id)).get();
            outdated.remove(phoneNumberId);
            return answer;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RpcException rpc && RpcException.METHOD_NOT_FOUND.equals(rpc.code())) {
                if (outdated.add(phoneNumberId)) {
                    log.warn("the extension on number {} cannot look up contacts' other WhatsApp ids until it is "
                            + "updated", phoneNumberId);
                }
                return null;
            }
            log.warn("{} for contact {} on number {} failed: {}", METHOD, contactId, phoneNumberId, cause.toString());
            return null;
        }
    }

    /**
     * The one place that decides what an answered id does to the contact: on it already, nothing; held by another
     * contact (its phone or one of its ids), the two are merged and the survivor goes on; stashed by a merge under
     * way, left to that merge; held by nobody, added (with the phone, for a phone id, when the contact has none).
     */
    private void apply(String workspaceId, String contactId, List<String> ids) {
        String current = contactId;
        for (String id : ids) {
            Contact c = contacts.findByIdAndWorkspaceId(current, workspaceId).orElse(null);
            if (c == null || c.getMergingInto() != null) return; // merged away meanwhile
            if (c.getWaIds().contains(id)) continue;
            Contact holder = holderOf(workspaceId, id);
            if (holder != null && !holder.getId().equals(c.getId())) {
                String survivorId = ContactMergeService.survivorOf(c, holder);
                String otherId = survivorId.equals(c.getId()) ? holder.getId() : c.getId();
                if (!merge(workspaceId, survivorId, otherId)) return;
                current = survivorId;
            } else if (holder != null || !stashedByMerge(workspaceId, id)) {
                if (!add(workspaceId, current, id)) return;
            }
        }
    }

    /** The live contact the id belongs to: for a phone id the contact of that phone, else the one listing it. */
    private Contact holderOf(String workspaceId, String id) {
        if (id.endsWith(PHONE_SUFFIX)) {
            Contact byPhone = contacts.findByWorkspaceIdAndPhone(workspaceId, digits(id)).orElse(null);
            if (byPhone != null) return byPhone;
        }
        return contacts.findByWorkspaceIdAndWaIdsContaining(workspaceId, id).orElse(null);
    }

    /** Whether a contact being merged away let go of the id (or, for a phone id, its phone) to its survivor. */
    private boolean stashedByMerge(String workspaceId, String id) {
        Criteria held = id.endsWith(PHONE_SUFFIX)
                ? new Criteria().orOperator(Criteria.where("mergingWaIds").is(id),
                        Criteria.where("mergingPhone").is(digits(id)))
                : Criteria.where("mergingWaIds").is(id);
        return mongo.exists(new Query(Criteria.where("workspaceId").is(workspaceId).and("mergingInto").exists(true))
                .addCriteria(held), Contact.class);
    }

    /** False when another contact took the id meanwhile (logged); the next attempt decides again. */
    private boolean add(String workspaceId, String contactId, String id) {
        try {
            InboxIngestService.retrying(() -> {
                Contact c = contacts.findByIdAndWorkspaceId(contactId, workspaceId).orElse(null);
                if (c == null) return null;
                boolean changed = ContactService.addWaId(c, id);
                if (c.getPhone() == null && id.endsWith(PHONE_SUFFIX)
                        && contacts.findByWorkspaceIdAndPhone(workspaceId, digits(id)).isEmpty()) {
                    c.setPhone(digits(id));
                    changed = true;
                }
                return changed ? contacts.save(c) : c;
            });
            log.info("contact {} in {} took its other WhatsApp id", contactId, workspaceId);
            return true;
        } catch (DuplicateKeyException e) {
            log.warn("contact {} in {} did not take its other WhatsApp id: another contact took it meanwhile",
                    contactId, workspaceId);
            return false;
        }
    }

    /** False when the merge stopped because a third contact holds a stashed id ({@link ContactMergeService} logs it). */
    private boolean merge(String workspaceId, String survivorId, String otherId) {
        try {
            merges.merge(workspaceId, survivorId, otherId);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** Contact id to one of its chats' numbers whose device is ready now. */
    private Map<String, String> readyNumberByContact(String workspaceId) {
        List<String> ready = numbers.findByWorkspaceIdOrderByNumber(workspaceId).stream().map(PhoneNumber::getId)
                .filter(id -> devices.isReady(workspaceId, id)).toList();
        Map<String, String> out = new HashMap<>();
        if (ready.isEmpty()) return out;
        Query q = new Query(Criteria.where("workspaceId").is(workspaceId).and("phoneNumberId").in(ready)
                .and("contactId").ne(null));
        q.fields().include("contactId", "phoneNumberId");
        try (Stream<WaWebChat> chats = mongo.stream(q, WaWebChat.class)) {
            chats.forEach(chat -> out.putIfAbsent(chat.getContactId(), chat.getPhoneNumberId()));
        }
        return out;
    }

    /** Runs resolver work, logging a failure once; a stop of the application is not a failure. */
    private static void guarded(String contactId, Runnable work) {
        try {
            work.run();
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) return;
            log.warn("resolving the WhatsApp ids of contact {} failed: {}", contactId, e.toString());
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    private static String digits(String waId) {
        return waId.substring(0, waId.indexOf('@'));
    }
}
