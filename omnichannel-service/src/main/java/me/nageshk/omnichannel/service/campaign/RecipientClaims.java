package me.nageshk.omnichannel.service.campaign;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import me.nageshk.omnichannel.db.campaign.AudienceKind;
import me.nageshk.omnichannel.db.campaign.Campaign;
import me.nageshk.omnichannel.db.campaign.CampaignCounter;
import me.nageshk.omnichannel.db.campaign.CampaignRecipient;
import me.nageshk.omnichannel.db.campaign.CampaignSender;
import me.nageshk.omnichannel.db.campaign.CampaignSettings;
import me.nageshk.omnichannel.db.campaign.CampaignStatus;
import me.nageshk.omnichannel.db.campaign.PausedBy;
import me.nageshk.omnichannel.db.campaign.RecipientStatus;
import me.nageshk.omnichannel.db.campaign.SkipReason;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactRepository;
import me.nageshk.omnichannel.db.inbox.MessageStatus;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatRepository;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.db.inbox.WaWebMessageRepository;
import me.nageshk.omnichannel.service.contact.ContactService;
import me.nageshk.omnichannel.service.inbox.MessageView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * What every kind of number does with a campaign's recipients: claims them, undoes a claim, records skips, failures
 * and sends, and pauses a campaign in the system's name. Every write after a claim is conditional on the recipient
 * still being SENDING, so a path that got there first (failing it as stuck, say) wins.
 */
@Component
class RecipientClaims {

    private static final Logger log = LoggerFactory.getLogger(RecipientClaims.class);

    private final MongoTemplate mongo;
    private final CampaignCounter counter;
    private final ContactService contactService;
    private final ContactRepository contacts;
    private final WaWebChatRepository chats;
    private final WaWebMessageRepository messages;
    private final CampaignObserver observer;
    private final Clock clock;
    /** {@code workspaceId/phone} of the recipients being handled now, so two senders never message one phone at once. */
    private final Set<String> phonesInFlight = ConcurrentHashMap.newKeySet();

    RecipientClaims(MongoTemplate mongo, CampaignCounter counter, ContactService contactService,
            ContactRepository contacts, WaWebChatRepository chats, WaWebMessageRepository messages,
            CampaignObserver observer, Clock clock) {
        this.mongo = mongo;
        this.counter = counter;
        this.contactService = contactService;
        this.contacts = contacts;
        this.chats = chats;
        this.messages = messages;
        this.observer = observer;
        this.clock = clock;
    }

    boolean isRunning(Campaign c) {
        return mongo.exists(new Query(Criteria.where("_id").is(c.getId()).and("status").is(CampaignStatus.RUNNING)),
                Campaign.class);
    }

    /** The oldest campaign's first PENDING recipient that waits for no other sender, now SENDING; null when none. */
    CampaignClaim claim(String senderId, CampaignSender sender, List<Campaign> campaigns, CampaignSettings settings) {
        for (Campaign c : campaigns) {
            Query q = new Query(Criteria.where("campaignId").is(c.getId()).and("status").is(RecipientStatus.PENDING)
                    .and("preferredSenderId").in(null, senderId)).with(Sort.by("_id"));
            Update u = new Update().set("status", RecipientStatus.SENDING).set("senderId", senderId)
                    .set("attemptedAt", clock.instant());
            CampaignRecipient r = mongo.findAndModify(q, u, FindAndModifyOptions.options().returnNew(true),
                    CampaignRecipient.class);
            if (r != null) return new CampaignClaim(c, r, senderId, sender, settings);
        }
        return null;
    }

    /** Marks the claim's phone as being handled; false when another sender is on the same phone of the workspace. */
    boolean enterPhone(CampaignClaim claim) {
        return phonesInFlight.add(phoneKey(claim));
    }

    void leavePhone(CampaignClaim claim) {
        phonesInFlight.remove(phoneKey(claim));
        counter.refresh(claim.campaign().getId());
    }

    private static String phoneKey(CampaignClaim claim) {
        return claim.campaign().getWorkspaceId() + "/" + claim.recipient().getPhone();
    }

    boolean freeSkip(CampaignClaim claim, SkipReason reason) {
        whileSending(claim, new Update().set("status", RecipientStatus.SKIPPED).set("skipReason", reason)
                .unset("attemptedAt"));
        return true;
    }

    /** Nothing reached the device: the claim is undone as if it never happened. */
    void release(CampaignClaim claim) {
        whileSending(claim, new Update().set("status", RecipientStatus.PENDING).unset("senderId")
                .unset("attemptedAt").unset("dispatchedAt"));
    }

    /** Failed before anything was sent: no gap. */
    void failed(CampaignClaim claim, String error) {
        whileSending(claim, new Update().set("status", RecipientStatus.FAILED).set("error", error));
    }

    /** Pauses a campaign that is still running, in the system's name. */
    void pause(String campaignId, String reason) {
        mongo.updateFirst(new Query(Criteria.where("_id").is(campaignId).and("status").is(CampaignStatus.RUNNING)),
                new Update().set("status", CampaignStatus.PAUSED).set("pausedBy", PausedBy.SYSTEM)
                        .set("pauseReason", reason).set("lastUpdateTime", clock.instant()).inc("version", 1),
                Campaign.class);
    }

    /**
     * The message was sent at {@code now}: the recipient is SENT with its chat, message and contact, an ack the
     * device already reported is carried over, the observer hears of it, and a CSV row's name fills a blank contact.
     */
    void sent(CampaignClaim claim, MessageView message, Instant now) {
        CampaignRecipient r = claim.recipient();
        WaWebChat chat = chats.findById(message.chatId()).orElse(null);
        String contactId = r.getContactId() != null ? r.getContactId() : chat == null ? null : chat.getContactId();
        boolean recorded = whileSending(claim, new Update().set("status", RecipientStatus.SENT)
                .set("chatId", message.chatId()).set("messageId", message.id()).set("sentAt", now)
                .set("contactId", contactId));
        if (recorded) alreadyAcknowledged(r, message.id());
        Campaign.Audience a = claim.campaign().getAudience();
        if (recorded && contactId != null) {
            try {
                observer.sent(new CampaignObserver.CampaignSent(claim.campaign().getWorkspaceId(),
                        claim.campaign().getId(), claim.campaign().getName(), a == null ? null : a.segmentId(),
                        a == null ? null : a.segmentName(), contactId, claim.senderId(), now));
            } catch (RuntimeException e) {
                log.warn("campaign send observer failed for recipient {}", r.getId(), e);
            }
        }
        if (a != null && a.kind() == AudienceKind.CSV && a.nameColumn() != null && chat != null) {
            contactService.nameIfBlank(claim.campaign().getWorkspaceId(), chat.getContactId(),
                    PlaceholderValues.cell(a, r, a.nameColumn()));
        }
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

    /** Applies the update if the recipient is still SENDING; false (and logged) when another path got there first. */
    boolean whileSending(CampaignClaim claim, Update update) {
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

    Contact contactOf(CampaignRecipient r) {
        if (r.getContactId() != null) {
            Contact c = contacts.findByIdAndWorkspaceId(r.getContactId(), r.getWorkspaceId()).orElse(null);
            if (c != null) return c;
        }
        return contacts.findByWorkspaceIdAndPhone(r.getWorkspaceId(), r.getPhone()).orElse(null);
    }
}
