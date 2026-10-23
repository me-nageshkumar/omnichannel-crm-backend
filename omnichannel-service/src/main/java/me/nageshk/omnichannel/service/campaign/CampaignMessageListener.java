package me.nageshk.omnichannel.service.campaign;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import me.nageshk.omnichannel.db.campaign.CampaignCounter;
import me.nageshk.omnichannel.db.campaign.CampaignRecipient;
import me.nageshk.omnichannel.db.campaign.RecipientStatus;
import me.nageshk.omnichannel.db.campaign.SkipReason;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.DoNotContact;
import me.nageshk.omnichannel.db.inbox.MessageDirection;
import me.nageshk.omnichannel.db.inbox.MessageStatus;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.db.inbox.WaWebMessageRepository;
import me.nageshk.omnichannel.service.contact.ContactService;
import me.nageshk.omnichannel.service.inbox.InboxEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Follows campaign messages after they are sent: acks move the recipient to delivered and read, the first answer is
 * recorded as a reply, and a stop word to the latest campaign message marks the contact do-not-contact and skips
 * their pending recipients everywhere. It runs on the ingest thread, so it logs and swallows every
 * {@link RuntimeException}.
 */
@Component
public class CampaignMessageListener {

    private static final Logger log = LoggerFactory.getLogger(CampaignMessageListener.class);
    /** Punctuation, symbols (emoji included), format characters and spaces around a stop word. */
    private static final Pattern EDGES =
            Pattern.compile("^[\\p{P}\\p{S}\\p{Cf}\\p{Z}\\s\\x{FE0E}\\x{FE0F}]+|[\\p{P}\\p{S}\\p{Cf}\\p{Z}\\s\\x{FE0E}\\x{FE0F}]+$");

    private final MongoTemplate mongo;
    private final WaWebMessageRepository messages;
    private final CampaignCounter counter;
    private final CampaignSettingsService settingsService;
    private final ContactService contacts;
    private final Clock clock;

    CampaignMessageListener(MongoTemplate mongo, WaWebMessageRepository messages, CampaignCounter counter,
            CampaignSettingsService settingsService, ContactService contacts, Clock clock) {
        this.mongo = mongo;
        this.messages = messages;
        this.counter = counter;
        this.settingsService = settingsService;
        this.contacts = contacts;
        this.clock = clock;
    }

    @EventListener
    public void on(InboxEvents.MessageUpserted e) {
        try {
            WaWebMessage m = e.message();
            if (m.getDirection() == MessageDirection.OUT) {
                if (m.getCampaignId() != null) acked(m);
            } else if (m.getDirection() == MessageDirection.IN && e.inserted()) {
                replied(e.chat(), m);
            }
        } catch (RuntimeException ex) {
            log.warn("could not apply message {} to its campaign", e.message().getWaMessageId(), ex);
        }
    }

    private void acked(WaWebMessage m) {
        MessageStatus s = m.getStatus();
        Instant now = clock.instant();
        Query byMessage = new Query(Criteria.where("messageId").is(m.getId()));
        boolean changed = false;
        if (s == MessageStatus.DELIVERED) {
            changed = update(byMessage, RecipientStatus.SENT, new Update().set("status", RecipientStatus.DELIVERED)
                    .set("deliveredAt", now));
        } else if (s == MessageStatus.READ || s == MessageStatus.PLAYED) {
            changed = update(byMessage, RecipientStatus.SENT, new Update().set("status", RecipientStatus.READ)
                    .set("readAt", now).set("deliveredAt", now));
            changed |= update(byMessage, RecipientStatus.DELIVERED,
                    new Update().set("status", RecipientStatus.READ).set("readAt", now));
        }
        if (changed) counter.refresh(m.getCampaignId());
    }

    private boolean update(Query byMessage, RecipientStatus from, Update update) {
        Query q = Query.of(byMessage).addCriteria(Criteria.where("status").is(from));
        return mongo.updateFirst(q, update, CampaignRecipient.class).getModifiedCount() > 0;
    }

    private void replied(WaWebChat chat, WaWebMessage inbound) {
        WaWebMessage lastOut = messages
                .findFirstByChatIdAndDirectionOrderByTimestampDesc(chat.getId(), MessageDirection.OUT).orElse(null);
        String campaignLast = lastOut == null ? null : lastOut.getCampaignId();
        if (chat.getCampaignId() == null && campaignLast == null) return;
        CampaignRecipient latest = mongo.findOne(new Query(Criteria.where("chatId").is(chat.getId())
                .and("status").in(RecipientStatus.SENT, RecipientStatus.DELIVERED, RecipientStatus.READ))
                .with(Sort.by(Sort.Direction.DESC, "sentAt")), CampaignRecipient.class);
        if (latest != null && latest.getRepliedAt() == null) {
            long n = mongo.updateFirst(new Query(Criteria.where("_id").is(latest.getId()).and("repliedAt").is(null)),
                    new Update().set("repliedAt", clock.instant()), CampaignRecipient.class).getModifiedCount();
            if (n > 0) counter.refresh(latest.getCampaignId());
        }
        if (campaignLast != null && isStopWord(chat.getWorkspaceId(), inbound.getBody())) {
            stop(chat, latest, campaignLast);
        }
    }

    /** Whether the whole message is a stop word, ignoring case and the punctuation and symbols around it. */
    private boolean isStopWord(String workspaceId, String body) {
        if (body == null) return false;
        String text = bare(body);
        if (text.isEmpty()) return false;
        List<String> stopWords = settingsService.get(workspaceId).getStopWords();
        return stopWords != null && stopWords.stream().anyMatch(w -> w != null && text.equals(bare(w)));
    }

    static String bare(String text) {
        return EDGES.matcher(text.toLowerCase(Locale.ROOT)).replaceAll("");
    }

    private void stop(WaWebChat chat, CampaignRecipient latest, String campaignId) {
        String ws = chat.getWorkspaceId();
        Contact contact = chat.getContactId() == null ? null
                : mongo.findOne(new Query(Criteria.where("_id").is(chat.getContactId()).and("workspaceId").is(ws)),
                        Contact.class);
        String phone = latest != null && latest.getPhone() != null ? latest.getPhone()
                : contact == null ? null : contact.getPhone();
        if (contact != null) contacts.markDoNotContact(ws, contact.getId(), DoNotContact.Reason.STOP_REPLY, campaignId);
        if (phone == null) return;
        Query pending = new Query(Criteria.where("workspaceId").is(ws).and("phone").is(phone)
                .and("status").is(RecipientStatus.PENDING));
        Set<String> touched = new LinkedHashSet<>(mongo.findDistinct(pending, "campaignId", CampaignRecipient.class,
                String.class));
        if (touched.isEmpty()) return;
        mongo.updateMulti(pending, new Update().set("status", RecipientStatus.SKIPPED)
                .set("skipReason", SkipReason.DO_NOT_CONTACT), CampaignRecipient.class);
        for (String id : touched) counter.refresh(id);
    }
}
