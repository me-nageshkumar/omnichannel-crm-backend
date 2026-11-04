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
 * A contact is queued once at a time: while its work waits or runs, asking again queues nothing. All of this is kept
 * in memory and starts over on a restart.
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
    private final int batch;

    /** When each contact (by id) was last looked up, whatever came of it. */
    private final ConcurrentHashMap<String, Instant> lastAttempt = new ConcurrentHashMap<>();
    /** When each number's device was last asked ({@link System#nanoTime()}); only the resolver thread uses it. */
    private final Map<String, Long> lastCall = new ConcurrentHashMap<>();
    /** Contacts (by id) whose work is queued or running; another request for them queues nothing. */
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
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
        this.batch = props.contacts().resolveBatch();
    }

    /**
     * Queues a lookup of the contact's other id on the number's device, whenever it was last tried, and counts it as
     * an attempt; when the contact is already queued or being looked up, queues nothing and completes at once. The
     * future completes when the lookup is done; a missing device, a failed call or an answer that changes nothing
     * complete it normally (each failure is logged once, here).
     */
    public CompletableFuture<Void> resolve(String workspaceId, String phoneNumberId, String contactId) {
        CompletableFuture<Void> queued = queueLookUp(workspaceId, phoneNumberId, contactId);
        return queued != null ? queued : CompletableFuture.completedFuture(null);
    }

    /** The lookup's future, or null when the contact was already pending. */
    private CompletableFuture<Void> queueLookUp(String workspaceId, String phoneNumberId, String contactId) {
        if (!pending.add(contactId)) return null;
        lastAttempt.put(contactId, clock.instant());
        try {
            return CompletableFuture.runAsync(
                    () -> once(contactId, () -> lookUp(workspaceId, phoneNumberId, contactId)), executor);
        } catch (RuntimeException e) {
            pending.remove(contactId);
            throw e;
        }
    }

    /** Runs {@code work} on the resolver thread unless the contact is already pending; false when it was. */
    private boolean queue(String contactId, Runnable work) {
        if (!pending.add(contactId)) return false;
        try {
            executor.execute(() -> once(contactId, work));
            return true;
        } catch (RuntimeException e) {
            pending.remove(contactId);
            throw e;
        }
    }

    /** The contact's queued work: guarded, and no longer pending once it is done. */
    private void once(String contactId, Runnable work) {
        try {
            guarded(contactId, work);
        } finally {
            pending.remove(contactId);
        }
    }

    /**
     * After an inbound message is stored: returns at once; on the resolver's thread, looks the contact up when it
     * lacks a phone or a hidden id, the number's device is ready and its last attempt is {@code resolveRetry} old.
     * Otherwise nothing happens and a later message tries again.
     */
    void afterMessage(String workspaceId, String phoneNumberId, String contactId) {
        queue(contactId, () -> {
            Contact c = contacts.findByIdAndWorkspaceId(contactId, workspaceId).orElse(null);
            if (c == null || !unresolved(c) || !devices.isReady(workspaceId, phoneNumberId) || !claim(contactId)) {
                return;
            }
            lookUp(workspaceId, phoneNumberId, contactId);
        });
    }

    /**
     * Queues up to {@code resolveBatch} contacts of the brand that lack a phone (first) or a hidden id, chat on a
     * number whose device is ready now (on one such number) and were not tried within {@code resolveRetry}; and
     * finishes every merge left half done ({@code mergingInto} set), outside that cap. A contact already pending is
     * skipped. Returns how many were queued.
     */
    public int queueBrand(String workspaceId) {
        int queued = 0;
        try (Stream<Contact> merging = contacts.streamMergingByWorkspaceId(workspaceId)) {
            for (Contact m : (Iterable<Contact>) merging::iterator) {
                String survivorId = m.getMergingInto();
                String otherId = m.getId();
                if (queue(otherId, () -> merge(workspaceId, survivorId, otherId))) queued++;
            }
        }
        Map<String, String> readyNumberOf = readyNumberByContact(workspaceId);
        if (readyNumberOf.isEmpty()) return queued;
        int lookUps = 0;
        try (Stream<Contact> phoneless = contacts.streamPhonelessByWorkspaceId(workspaceId)) {
            lookUps = queueLookUps(workspaceId, phoneless, readyNumberOf, lookUps);
        }
        if (lookUps < batch) {
            try (Stream<Contact> lidless = contacts.streamLidlessByWorkspaceId(workspaceId)) {
                lookUps = queueLookUps(workspaceId, lidless, readyNumberOf, lookUps);
            }
        }
        return queued + lookUps;
    }

    /** Queues lookups from {@code candidates} until {@code resolveBatch} are queued; returns the new count. */
    private int queueLookUps(String workspaceId, Stream<Contact> candidates, Map<String, String> readyNumberOf,
            int queued) {
        Instant now = clock.instant();
        for (Contact c : (Iterable<Contact>) candidates::iterator) {
            if (queued >= batch) break;
            String phoneNumberId = readyNumberOf.get(c.getId());
            if (phoneNumberId == null || askWith(c) == null || !due(c.getId(), now)) continue;
            if (queueLookUp(workspaceId, phoneNumberId, c.getId()) != null) queued++;
        }
        return queued;
    }

    /** Whether the contact was not tried within {@code resolveRetry} (nothing is recorded). */
    private boolean due(String contactId, Instant now) {
        Instant last = lastAttempt.get(contactId);
        return last == null || !now.isBefore(last.plus(retry));
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
     * contact (its phone or one of its ids), the two are merged and the survivor goes on, unless both hold different
     * phones (logged and left: never merged automatically); stashed by a merge under way, left to that merge; held
     * by nobody, added (with the phone, for a phone id, when the contact has none).
     */
    private void apply(String workspaceId, String contactId, List<String> ids) {
        String current = contactId;
        for (String id : ids) {
            Contact c = contacts.findByIdAndWorkspaceId(current, workspaceId).orElse(null);
            if (c == null || c.getMergingInto() != null) return; // merged away meanwhile
            if (c.getWaIds().contains(id)) continue;
            Contact holder = holderOf(workspaceId, id, c.getId());
            if (holder != null && !holder.getId().equals(c.getId()) && differentPhones(c, holder)) {
                // a number change, or two people: merging would drop one phone into a deleted contact's stash
                log.warn("contacts {} and {} in {} both answer to {} but hold different phones; not merged",
                        c.getId(), holder.getId(), workspaceId, id);
            } else if (holder != null && !holder.getId().equals(c.getId())) {
                String survivorId = ContactMergeService.survivorOf(c, holder);
                String otherId = survivorId.equals(c.getId()) ? holder.getId() : c.getId();
                if (!merge(workspaceId, survivorId, otherId)) return;
                current = survivorId;
            } else if (holder != null || !stashedByMerge(workspaceId, id)) {
                add(workspaceId, current, id); // a clash is logged; the remaining ids are still decided
            }
        }
    }

    private static boolean differentPhones(Contact a, Contact b) {
        return a.getPhone() != null && b.getPhone() != null && !a.getPhone().equals(b.getPhone());
    }

    /**
     * The live contact the id belongs to: for a phone id the contact of that phone, else the one listing it. When the
     * phone's contact is {@code selfId} itself, another contact may still list the phone id (ingest leaves such a
     * pair rather than reject a message); that one is the holder, so the pair is settled instead of the id clashing.
     */
    private Contact holderOf(String workspaceId, String id, String selfId) {
        Contact byPhone = id.endsWith(PHONE_SUFFIX)
                ? contacts.findByWorkspaceIdAndPhone(workspaceId, digits(id)).orElse(null)
                : null;
        if (byPhone != null && !byPhone.getId().equals(selfId)) return byPhone;
        Contact listing = contacts.findByWorkspaceIdAndWaIdsContaining(workspaceId, id).orElse(null);
        return listing != null ? listing : byPhone;
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

    /** Adds the id; when another contact took it meanwhile, logs that and leaves it to the next attempt. */
    private void add(String workspaceId, String contactId, String id) {
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
        } catch (DuplicateKeyException e) {
            log.warn("contact {} in {} did not take its other WhatsApp id: another contact took it meanwhile",
                    contactId, workspaceId);
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
