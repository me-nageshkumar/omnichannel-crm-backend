package me.nageshk.omnichannel.service.campaign;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import me.nageshk.omnichannel.common.api.NoDeviceException;
import me.nageshk.omnichannel.common.config.OmnichannelProperties;
import me.nageshk.omnichannel.common.inbox.LookupFailedException;
import me.nageshk.omnichannel.common.rpc.RpcException;
import me.nageshk.omnichannel.db.campaign.AudienceKind;
import me.nageshk.omnichannel.db.campaign.Campaign;
import me.nageshk.omnichannel.db.campaign.CampaignCounter;
import me.nageshk.omnichannel.db.campaign.CampaignRecipient;
import me.nageshk.omnichannel.db.campaign.CampaignSender;
import me.nageshk.omnichannel.db.campaign.CampaignSettings;
import me.nageshk.omnichannel.db.campaign.CampaignStatus;
import me.nageshk.omnichannel.db.campaign.PausedBy;
import me.nageshk.omnichannel.db.campaign.RecipientSkips;
import me.nageshk.omnichannel.db.campaign.RecipientStatus;
import me.nageshk.omnichannel.db.campaign.SenderState;
import me.nageshk.omnichannel.db.campaign.SkipReason;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactRepository;
import me.nageshk.omnichannel.db.inbox.MessageStatus;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatRepository;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.db.inbox.WaWebMessageRepository;
import me.nageshk.omnichannel.service.contact.ContactService;
import me.nageshk.omnichannel.service.inbox.CampaignActor;
import me.nageshk.omnichannel.service.inbox.InboxDevices;
import me.nageshk.omnichannel.service.inbox.MessageView;
import me.nageshk.omnichannel.service.inbox.NewChatService;
import me.nageshk.omnichannel.service.inbox.NumberLookup;
import me.nageshk.omnichannel.service.inbox.NumberLookupService;
import me.nageshk.omnichannel.service.inbox.SendRequest;
import me.nageshk.omnichannel.service.template.RenderedTemplate;
import me.nageshk.omnichannel.service.template.TemplateRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Sends the RUNNING campaigns, one recipient per eligible sender per tick: claims it, skips it for free when it must
 * not or cannot be messaged, looks the number up, renders, sends and paces the sender. Each sender's work runs on
 * {@code campaignScheduler}; a sender still at work from an earlier tick is left alone. Every write to a recipient
 * after a device call is conditional on it still being SENDING, so a path that got there first (failing it as stuck,
 * say) wins.
 */
@Service
public class CampaignRunner {

    static final int FREE_SKIPS = 20;
    static final String SEND_REFUSED = "SEND_REFUSED";

    private static final Logger log = LoggerFactory.getLogger(CampaignRunner.class);
    private static final Duration STUCK_MARGIN = Duration.ofMinutes(2);
    private static final List<RecipientStatus> OPEN = List.of(RecipientStatus.PENDING, RecipientStatus.SENDING);

    /** A recipient this sender claimed, with its campaign. */
    private record Claim(Campaign campaign, CampaignRecipient recipient, CampaignSender sender,
            CampaignSettings settings) {

        String senderId() {
            return sender.getPhoneNumberId();
        }
    }

    private final MongoTemplate mongo;
    private final CampaignSenderService senders;
    private final CampaignSettingsService settingsService;
    private final CampaignCounter counter;
    private final RecipientSkips skips;
    private final PlaceholderValues placeholderValues;
    private final NumberLookupService lookups;
    private final NewChatService newChats;
    private final TemplateRenderer renderer;
    private final InboxDevices devices;
    private final ContactService contactService;
    private final ContactRepository contacts;
    private final WaWebChatRepository chats;
    private final WaWebMessageRepository messages;
    private final PacingRandom random;
    private final SenderHealth health;
    private final Clock clock;
    private final Executor executor;
    private final Duration stuckAfter;
    /** Senders whose work of an earlier tick has not finished. */
    private final Set<String> busy = ConcurrentHashMap.newKeySet();
    /** {@code workspaceId/phone} of the recipients being handled now, so two senders never message one phone at once. */
    private final Set<String> phonesInFlight = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean ticking = new AtomicBoolean();

    CampaignRunner(MongoTemplate mongo, CampaignSenderService senders, CampaignSettingsService settingsService,
            CampaignCounter counter, RecipientSkips skips, PlaceholderValues placeholderValues,
            NumberLookupService lookups, NewChatService newChats, TemplateRenderer renderer, InboxDevices devices,
            ContactService contactService, ContactRepository contacts, WaWebChatRepository chats,
            WaWebMessageRepository messages, PacingRandom random, SenderHealth health, Clock clock,
            @Qualifier("campaignScheduler") Executor executor, OmnichannelProperties props) {
        this.mongo = mongo;
        this.senders = senders;
        this.settingsService = settingsService;
        this.counter = counter;
        this.skips = skips;
        this.placeholderValues = placeholderValues;
        this.lookups = lookups;
        this.newChats = newChats;
        this.renderer = renderer;
        this.devices = devices;
        this.contactService = contactService;
        this.contacts = contacts;
        this.chats = chats;
        this.messages = messages;
        this.random = random;
        this.health = health;
        this.clock = clock;
        this.executor = executor;
        this.stuckAfter = props.rpc().timeout().plus(STUCK_MARGIN);
    }

    // ---- the tick ----

    /**
     * Starts each eligible sender's work and completes the campaigns that have nothing left. The future completes
     * when the work started by this tick has finished; a tick while another is still starting does nothing.
     */
    public CompletableFuture<Void> tick() {
        if (!ticking.compareAndSet(false, true)) return CompletableFuture.completedFuture(null);
        try {
            Instant now = clock.instant();
            List<Campaign> running = mongo.find(new Query(Criteria.where("status").is(CampaignStatus.RUNNING))
                    .with(Sort.by("startedAt", "_id")), Campaign.class);
            failStuck(now);
            Map<String, List<Campaign>> byWorkspace = new LinkedHashMap<>();
            for (Campaign c : running) byWorkspace.computeIfAbsent(c.getWorkspaceId(), k -> new ArrayList<>()).add(c);
            List<CompletableFuture<Void>> started = new ArrayList<>();
            byWorkspace.forEach((ws, campaigns) -> {
                try {
                    startSenders(ws, campaigns, now, started);
                } catch (RuntimeException e) {
                    log.warn("campaign tick of workspace {} failed", ws, e);
                }
            });
            try {
                skipUnusablePreferred(running);
            } catch (RuntimeException e) {
                log.warn("could not skip the recipients waiting for senders that cannot send", e);
            }
            completeFinished(running);
            return CompletableFuture.allOf(started.toArray(CompletableFuture[]::new));
        } finally {
            ticking.set(false);
        }
    }

    /** SENDING for longer than a device call can take: failed as INTERRUPTED, never retried. */
    public int failStuck(Instant now) {
        Query stuck = new Query(Criteria.where("status").is(RecipientStatus.SENDING)
                .and("attemptedAt").lt(now.minus(stuckAfter)));
        List<String> campaignIds = mongo.findDistinct(stuck, "campaignId", CampaignRecipient.class, String.class);
        if (campaignIds.isEmpty()) return 0;
        long failed = mongo.updateMulti(stuck, new Update().set("status", RecipientStatus.FAILED)
                .set("error", "INTERRUPTED"), CampaignRecipient.class).getModifiedCount();
        campaignIds.forEach(counter::refresh);
        log.warn("failed {} campaign recipients stuck in SENDING as INTERRUPTED", failed);
        return (int) failed;
    }

    private void startSenders(String ws, List<Campaign> campaigns, Instant now,
            List<CompletableFuture<Void>> started) {
        CampaignSettings settings = settingsService.get(ws);
        if (!SenderPolicy.inWindow(settings, now)) return;
        Set<String> senderIds = new LinkedHashSet<>();
        for (Campaign c : campaigns) senderIds.addAll(c.getSenderIds());
        for (String senderId : senderIds) {
            if (busy.contains(senderId)) continue;
            CampaignSender sender = senders.refresh(senders.ensure(ws, senderId), settings, now);
            if (!eligible(ws, sender, settings, now)) continue;
            List<Campaign> mine = campaigns.stream().filter(c -> c.getSenderIds().contains(senderId)).toList();
            busy.add(senderId);
            try {
                started.add(CompletableFuture.runAsync(() -> {
                    try {
                        process(ws, sender, mine, settings);
                    } finally {
                        busy.remove(senderId);
                    }
                }, executor));
            } catch (RuntimeException rejected) {
                busy.remove(senderId);
                throw rejected;
            }
        }
    }

    private boolean eligible(String ws, CampaignSender s, CampaignSettings settings, Instant now) {
        String id = s.getPhoneNumberId();
        return (s.getState() == SenderState.WARMING || s.getState() == SenderState.ACTIVE)
                && devices.isReady(ws, id)
                && (s.getPausedUntil() == null || !s.getPausedUntil().isAfter(now))
                && !now.isBefore(SenderPolicy.dayStartsAt(settings, SenderPolicy.today(settings, now),
                        s.getDayStartOffsetMinutes()))
                && senders.usedToday(id, settings, now) < senders.capToday(s, settings, now)
                && senders.hourlyUsed(id, now) < settings.getHourlyCap()
                && (s.getNextSendAt() == null || !s.getNextSendAt().isAfter(now));
    }

    /**
     * PENDING recipients waiting for a preferred sender that will not send again (RETIRED, or its number disabled,
     * not WhatsApp Web or gone from the workspace) are skipped as chats on another number, so the campaign can finish.
     * A RESTING or SUSPENDED sender comes back, so its recipients keep waiting.
     */
    private void skipUnusablePreferred(List<Campaign> running) {
        Map<String, Boolean> usable = new HashMap<>();
        for (Campaign c : running) {
            List<String> preferred = mongo.findDistinct(new Query(Criteria.where("campaignId").is(c.getId())
                    .and("status").is(RecipientStatus.PENDING).and("preferredSenderId").ne(null)),
                    "preferredSenderId", CampaignRecipient.class, String.class);
            boolean skipped = false;
            for (String senderId : preferred) {
                if (usable.computeIfAbsent(c.getWorkspaceId() + "/" + senderId,
                        k -> senders.mayBeWaitedFor(c.getWorkspaceId(), senderId))) continue;
                skipped |= mongo.updateMulti(new Query(Criteria.where("campaignId").is(c.getId())
                                .and("status").is(RecipientStatus.PENDING).and("preferredSenderId").is(senderId)),
                        new Update().set("status", RecipientStatus.SKIPPED)
                                .set("skipReason", SkipReason.CHAT_ON_OTHER_NUMBER), CampaignRecipient.class)
                        .getModifiedCount() > 0;
            }
            if (skipped) counter.refresh(c.getId());
        }
    }

    /** RUNNING campaigns without a PENDING or SENDING recipient are COMPLETED. */
    private void completeFinished(List<Campaign> running) {
        for (Campaign c : running) {
            if (mongo.exists(new Query(Criteria.where("campaignId").is(c.getId()).and("status").in(OPEN)),
                    CampaignRecipient.class)) continue;
            Instant now = clock.instant();
            mongo.updateFirst(new Query(Criteria.where("_id").is(c.getId()).and("status").is(CampaignStatus.RUNNING)),
                    new Update().set("status", CampaignStatus.COMPLETED).set("finishedAt", now)
                            .set("lastUpdateTime", now).inc("version", 1), Campaign.class);
        }
    }

    // ---- one sender ----

    /**
     * Claims and handles recipients until one is not a free skip, at most {@link #FREE_SKIPS} free skips more. What
     * the tick saw may have changed while this sender waited for a thread: the sender is read again and does nothing
     * when it is no longer eligible (retired, paused, at a cap, in a gap); a claim is released when the window has
     * closed (and the sender stops), when its campaign is no longer RUNNING (and that campaign is passed over), or
     * when another sender is working on the same phone of the workspace (and this sender stops for the tick).
     */
    private void process(String ws, CampaignSender seen, List<Campaign> campaigns, CampaignSettings settings) {
        try {
            CampaignSender sender = senders.find(seen.getPhoneNumberId());
            if (sender == null || !eligible(ws, sender, settings, clock.instant())) return;
            List<Campaign> open = new ArrayList<>(campaigns);
            int freeSkips = FREE_SKIPS;
            while (true) {
                Claim claim = claim(sender, open, settings);
                if (claim == null) return;
                if (!SenderPolicy.inWindow(settings, clock.instant())) {
                    release(claim);
                    return;
                }
                if (!isRunning(claim.campaign())) {
                    release(claim);
                    open.remove(claim.campaign());
                    continue;
                }
                String phoneKey = claim.campaign().getWorkspaceId() + "/" + claim.recipient().getPhone();
                if (!phonesInFlight.add(phoneKey)) {
                    release(claim);
                    return;
                }
                boolean free;
                try {
                    free = handle(claim);
                } finally {
                    phonesInFlight.remove(phoneKey);
                    counter.refresh(claim.campaign().getId());
                }
                if (!free || freeSkips-- <= 0) return;
            }
        } catch (RuntimeException e) {
            log.warn("campaign work of sender {} failed", seen.getPhoneNumberId(), e);
        }
    }

    private boolean isRunning(Campaign c) {
        return mongo.exists(new Query(Criteria.where("_id").is(c.getId()).and("status").is(CampaignStatus.RUNNING)),
                Campaign.class);
    }

    /** The oldest campaign's first PENDING recipient that waits for no other sender, now SENDING; null when none. */
    private Claim claim(CampaignSender sender, List<Campaign> campaigns, CampaignSettings settings) {
        String senderId = sender.getPhoneNumberId();
        for (Campaign c : campaigns) {
            Query q = new Query(Criteria.where("campaignId").is(c.getId()).and("status").is(RecipientStatus.PENDING)
                    .and("preferredSenderId").in(null, senderId)).with(Sort.by("_id"));
            Update u = new Update().set("status", RecipientStatus.SENDING).set("senderId", senderId)
                    .set("attemptedAt", clock.instant());
            CampaignRecipient r = mongo.findAndModify(q, u, FindAndModifyOptions.options().returnNew(true),
                    CampaignRecipient.class);
            if (r != null) return new Claim(c, r, sender, settings);
        }
        return null;
    }

    /** Returns true when the recipient was skipped without asking the device and without a gap. */
    private boolean handle(Claim claim) {
        String ws = claim.campaign().getWorkspaceId();
        String phone = claim.recipient().getPhone();
        if (skips.isDoNotContact(ws, phone)) return freeSkip(claim, SkipReason.DO_NOT_CONTACT);
        if (skips.isRecentlyMessaged(ws, phone, clock.instant(), claim.settings().getRecontactDays())) {
            return freeSkip(claim, SkipReason.RECENTLY_MESSAGED);
        }
        NumberLookup lookup;
        try {
            lookup = await(lookups.lookup(ws, claim.senderId(), phone, false));
        } catch (NoDeviceException e) {
            release(claim);
            return false;
        } catch (ExecutionException | RuntimeException e) {
            lookupFailed(claim, e instanceof ExecutionException ? e.getCause() : e);
            return false;
        }
        if (!lookup.cached()) health.lookupSucceeded(claim.senderId());
        if (!lookup.registered()) {
            if (lookup.cached()) return freeSkip(claim, SkipReason.NOT_ON_WHATSAPP);
            notOnWhatsApp(claim);
            return false;
        }
        send(claim, lookup);
        return false;
    }

    private void send(Claim claim, NumberLookup lookup) {
        Campaign c = claim.campaign();
        CampaignRecipient r = claim.recipient();
        Map<String, String> values = placeholderValues.of(c, r, contactOf(r));
        try {
            RenderedTemplate rendered = renderer.renderForCampaign(c.getWorkspaceId(), r.getTemplateId(), values);
            if (CampaignLinks.containsLink(rendered.content().getBody())) {
                failed(claim, "HAS_LINK");
                return;
            }
        } catch (RuntimeException e) {
            log.warn("could not render template {} for recipient {}", r.getTemplateId(), r.getId(), e);
            failed(claim, "RENDER_FAILED");
            return;
        }
        CompletableFuture<MessageView> sent;
        // from here the message may reach the phone: it counts as recently messaged whatever becomes of the send
        if (!whileSending(claim, new Update().set("dispatchedAt", clock.instant()))) return;
        try {
            sent = newChats.startForCampaign(new CampaignActor(c.getId(), c.getName()), c.getWorkspaceId(),
                    claim.senderId(), r.getPhone(), lookup.remoteId(), new SendRequest(null, r.getTemplateId(), values));
        } catch (NoDeviceException e) {
            release(claim);
            return;
        } catch (RuntimeException e) {
            sendRefused(claim, e);
            return;
        }
        try {
            succeeded(claim, await(sent));
        } catch (ExecutionException e) {
            sendFailed(claim, e.getCause());
        }
    }

    // ---- outcomes ----

    private boolean freeSkip(Claim claim, SkipReason reason) {
        whileSending(claim, new Update().set("status", RecipientStatus.SKIPPED).set("skipReason", reason)
                .unset("attemptedAt"));
        return true;
    }

    /** Nothing reached the device: the claim is undone as if it never happened. */
    private void release(Claim claim) {
        whileSending(claim, new Update().set("status", RecipientStatus.PENDING).unset("senderId")
                .unset("attemptedAt").unset("dispatchedAt"));
    }

    /**
     * The sender could not look the number up: a fault of the sender, not of the lead. The claim is released and the
     * sender waits a gap; {@link SenderHealth#lookupFailed} pauses it on the third failure in a row.
     */
    private void lookupFailed(Claim claim, Throwable cause) {
        if (cause instanceof LookupFailedException) {
            log.warn("lookup for recipient {} on {} failed: {}", claim.recipient().getId(), claim.senderId(),
                    cause.toString());
        } else {
            log.warn("lookup for recipient {} on {} failed", claim.recipient().getId(), claim.senderId(), cause);
        }
        release(claim);
        Instant now = clock.instant();
        paced(claim, false, now);
        health.lookupFailed(claim.senderId(), claim.settings(), now);
    }

    /**
     * The send was refused before anything reached the device (a template archived since the start, say): the claim
     * is released at no cost and the campaign is paused for someone to look at it.
     */
    private void sendRefused(Claim claim, RuntimeException e) {
        log.warn("send to recipient {} of campaign {} was refused; pausing the campaign", claim.recipient().getId(),
                claim.campaign().getId(), e);
        release(claim);
        Instant now = clock.instant();
        mongo.updateFirst(new Query(Criteria.where("_id").is(claim.campaign().getId())
                        .and("status").is(CampaignStatus.RUNNING)),
                new Update().set("status", CampaignStatus.PAUSED).set("pausedBy", PausedBy.SYSTEM)
                        .set("pauseReason", SEND_REFUSED).set("lastUpdateTime", now).inc("version", 1),
                Campaign.class);
    }

    private void notOnWhatsApp(Claim claim) {
        whileSending(claim, new Update().set("status", RecipientStatus.SKIPPED)
                .set("skipReason", SkipReason.NOT_ON_WHATSAPP));
        paced(claim, true, clock.instant());
    }

    /** Failed before anything was sent: no gap. */
    private void failed(Claim claim, String error) {
        whileSending(claim, new Update().set("status", RecipientStatus.FAILED).set("error", error));
    }

    /** The device was asked to send and failed: the attempt costs its slot and its gap. */
    private void sendFailed(Claim claim, Throwable cause) {
        Throwable t = cause instanceof CompletionException && cause.getCause() != null ? cause.getCause() : cause;
        String error = t instanceof RpcException rpc ? rpc.code()
                : t instanceof TimeoutException ? "TIMEOUT" : t.getClass().getSimpleName();
        if (t instanceof RpcException || t instanceof TimeoutException) {
            log.warn("campaign send to recipient {} on {} failed: {}", claim.recipient().getId(), claim.senderId(),
                    t.toString());
        } else {
            log.warn("campaign send to recipient {} on {} failed", claim.recipient().getId(), claim.senderId(), t);
        }
        attempted(claim);
        failed(claim, error);
    }

    private void succeeded(Claim claim, MessageView message) {
        Instant now = attempted(claim);
        CampaignRecipient r = claim.recipient();
        WaWebChat chat = chats.findById(message.chatId()).orElse(null);
        String contactId = r.getContactId() != null ? r.getContactId() : chat == null ? null : chat.getContactId();
        boolean recorded = whileSending(claim, new Update().set("status", RecipientStatus.SENT)
                .set("chatId", message.chatId()).set("messageId", message.id()).set("sentAt", now)
                .set("contactId", contactId));
        if (recorded) alreadyAcknowledged(r, message.id());
        Campaign.Audience a = claim.campaign().getAudience();
        if (a != null && a.kind() == AudienceKind.CSV && a.nameColumn() != null && chat != null) {
            contactService.nameIfBlank(claim.campaign().getWorkspaceId(), chat.getContactId(),
                    PlaceholderValues.cell(a, r, a.nameColumn()));
        }
        health.afterSend(claim.senderId(), claim.settings(), now);
    }

    /** An ack that the device reported before the recipient was SENT is carried over now. */
    private void alreadyAcknowledged(CampaignRecipient r, String messageId) {
        WaWebMessage m = messages.findById(messageId).orElse(null);
        if (m == null || m.getStatus() == null) return;
        WaWebMessage.StatusTimes times = m.getStatusTimes() == null ? new WaWebMessage.StatusTimes() : m.getStatusTimes();
        Instant now = clock.instant();
        Instant deliveredAt = times.getDeliveredAt() != null ? times.getDeliveredAt() : now;
        if (m.getStatus() == MessageStatus.READ || m.getStatus() == MessageStatus.PLAYED) {
            Instant readAt = times.getReadAt() != null ? times.getReadAt()
                    : times.getPlayedAt() != null ? times.getPlayedAt() : now;
            mongo.updateFirst(byIdIn(r, RecipientStatus.SENT, RecipientStatus.DELIVERED),
                    new Update().set("status", RecipientStatus.READ).set("readAt", readAt), CampaignRecipient.class);
            mongo.updateFirst(byIdIn(r, RecipientStatus.READ).addCriteria(Criteria.where("deliveredAt").is(null)),
                    new Update().set("deliveredAt", deliveredAt), CampaignRecipient.class);
        } else if (m.getStatus() == MessageStatus.DELIVERED) {
            mongo.updateFirst(byIdIn(r, RecipientStatus.SENT), new Update().set("status", RecipientStatus.DELIVERED)
                    .set("deliveredAt", deliveredAt), CampaignRecipient.class);
        }
    }

    // ---- helpers ----

    /** Paces the sender and counts the attempt for its day (pacing first, so it is never left out); returns the time. */
    private Instant attempted(Claim claim) {
        Instant now = clock.instant();
        paced(claim, false, now);
        senders.sendAttempted(claim.senderId(), SenderPolicy.today(claim.settings(), now), now);
        return now;
    }

    /** The sender's next send after a gap from {@code now}: a skip's short one, doubled while at half pace. */
    private void paced(Claim claim, boolean skip, Instant now) {
        Instant halfPaceUntil = claim.sender().getHalfPaceUntil();
        boolean halfPace = halfPaceUntil != null && halfPaceUntil.isAfter(now);
        senders.paced(claim.senderId(),
                now.plusSeconds(SenderPolicy.gapSeconds(skip, halfPace, claim.settings(), random)));
    }

    /** Applies the update if the recipient is still SENDING; false (and logged) when another path got there first. */
    private boolean whileSending(Claim claim, Update update) {
        boolean matched = mongo.updateFirst(byIdIn(claim.recipient(), RecipientStatus.SENDING), update,
                CampaignRecipient.class).getMatchedCount() > 0;
        if (!matched) {
            log.info("recipient {} is no longer SENDING; its outcome was not recorded", claim.recipient().getId());
        }
        return matched;
    }

    private static Query byIdIn(CampaignRecipient r, RecipientStatus... statuses) {
        return new Query(Criteria.where("_id").is(r.getId()).and("status").in((Object[]) statuses));
    }

    private Contact contactOf(CampaignRecipient r) {
        if (r.getContactId() != null) {
            Contact c = contacts.findByIdAndWorkspaceId(r.getContactId(), r.getWorkspaceId()).orElse(null);
            if (c != null) return c;
        }
        return contacts.findByWorkspaceIdAndPhone(r.getWorkspaceId(), r.getPhone()).orElse(null);
    }

    /**
     * Waits for the device. An interrupted wait throws: during a lookup it counts as a failed lookup, during a send
     * it leaves the recipient SENDING for {@link #failStuck}, as the message may have gone out.
     */
    private static <T> T await(CompletableFuture<T> future) throws ExecutionException {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the device", e);
        }
    }
}
