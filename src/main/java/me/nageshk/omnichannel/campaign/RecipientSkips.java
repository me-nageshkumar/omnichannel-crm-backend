package me.nageshk.omnichannel.campaign;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import me.nageshk.omnichannel.contact.Contact;
import me.nageshk.omnichannel.inbox.WaWebChat;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Whether a recipient may still be messaged: do-not-contact, recently messaged and chats on other numbers.
 * Materialisation asks a page at a time; the runner asks again for one recipient at claim time.
 */
@Component
public class RecipientSkips {

    private static final List<RecipientStatus> SENT = List.of(RecipientStatus.SENT, RecipientStatus.DELIVERED,
            RecipientStatus.READ);
    /** Handed to the device without a known outcome: in flight, or failed after the device may have sent it. */
    private static final List<RecipientStatus> DISPATCHED = List.of(RecipientStatus.SENDING, RecipientStatus.FAILED);

    /** What one page of rows needs to be judged: the contacts by phone, the recently messaged phones and the chats. */
    public record Page(Map<String, Contact> contactsByPhone, Set<String> recentPhones,
            Map<String, List<WaWebChat>> chatsByContactId) {

        List<WaWebChat> chatsOf(Contact c) {
            return c == null ? List.of() : chatsByContactId.getOrDefault(c.getId(), List.of());
        }
    }

    private final MongoTemplate mongo;

    RecipientSkips(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /** One query each for the contacts (unless {@code known} are given), the recent recipients and the chats. */
    Page load(String workspaceId, Collection<String> phones, Collection<Contact> known, Instant now,
            int recontactDays) {
        Map<String, Contact> byPhone = new HashMap<>();
        if (known != null) {
            for (Contact c : known) if (c.getPhone() != null) byPhone.putIfAbsent(c.getPhone(), c);
        } else if (!phones.isEmpty()) {
            for (Contact c : mongo.find(new Query(Criteria.where("workspaceId").is(workspaceId).and("phone").in(phones)),
                    Contact.class)) {
                byPhone.putIfAbsent(c.getPhone(), c);
            }
        }
        Set<String> recent = phones.isEmpty() ? Set.of()
                : new HashSet<>(mongo.findDistinct(recentQuery(workspaceId, phones, now, recontactDays), "phone",
                        CampaignRecipient.class, String.class));
        List<String> contactIds = byPhone.values().stream().map(Contact::getId).toList();
        Map<String, List<WaWebChat>> chats = contactIds.isEmpty() ? Map.of()
                : mongo.find(new Query(Criteria.where("workspaceId").is(workspaceId).and("contactId").in(contactIds)),
                        WaWebChat.class).stream().collect(Collectors.groupingBy(WaWebChat::getContactId));
        return new Page(byPhone, recent, chats);
    }

    /** The first reason this contact must not be messaged by a campaign running on {@code senderIds}, or null. */
    SkipReason reason(Page page, String phone, Contact contact, Collection<String> senderIds) {
        if (contact != null && contact.getDoNotContact() != null) return SkipReason.DO_NOT_CONTACT;
        if (page.recentPhones().contains(phone)) return SkipReason.RECENTLY_MESSAGED;
        List<WaWebChat> chats = page.chatsOf(contact);
        if (!chats.isEmpty() && chats.stream().noneMatch(c -> senderIds.contains(c.getPhoneNumberId()))) {
            return SkipReason.CHAT_ON_OTHER_NUMBER;
        }
        return null;
    }

    /** The campaign sender whose chat with the contact was active most recently, or null when there is none. */
    String preferredSender(List<WaWebChat> chats, Collection<String> senderIds) {
        String best = null;
        Instant latest = null;
        for (WaWebChat c : chats) {
            if (!senderIds.contains(c.getPhoneNumberId())) continue;
            Instant at = c.getLastMessageAt() == null ? Instant.EPOCH : c.getLastMessageAt();
            if (latest == null || at.isAfter(latest)) {
                latest = at;
                best = c.getPhoneNumberId();
            }
        }
        return best;
    }

    /** Whether the brand's contact with this phone is marked do-not-contact now. */
    public boolean isDoNotContact(String workspaceId, String phone) {
        return mongo.exists(new Query(Criteria.where("workspaceId").is(workspaceId).and("phone").is(phone)
                .and("doNotContact").ne(null)), Contact.class);
    }

    /**
     * Whether the brand sent this phone a message in the last {@code recontactDays} days, in any campaign; a send
     * handed to the device that then failed or never finished counts, as the message may have gone out.
     */
    public boolean isRecentlyMessaged(String workspaceId, String phone, Instant now, int recontactDays) {
        return mongo.exists(recentQuery(workspaceId, List.of(phone), now, recontactDays), CampaignRecipient.class);
    }

    /** Each branch is served by its own index: {@code ws_phone_sent} and {@code ws_phone_dispatched}. */
    private static Query recentQuery(String workspaceId, Collection<String> phones, Instant now, int recontactDays) {
        Instant since = now.minus(Duration.ofDays(recontactDays));
        return new Query(new Criteria().orOperator(
                Criteria.where("workspaceId").is(workspaceId).and("phone").in(phones).and("status").in(SENT)
                        .and("sentAt").gte(since),
                Criteria.where("workspaceId").is(workspaceId).and("phone").in(phones).and("status").in(DISPATCHED)
                        .and("dispatchedAt").gte(since)));
    }
}
