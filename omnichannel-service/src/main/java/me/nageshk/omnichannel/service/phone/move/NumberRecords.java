package me.nageshk.omnichannel.service.phone.move;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import me.nageshk.omnichannel.db.inbox.ChatMediaConfig;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * The records keyed by a number's id that a move re-points (spec §7.2 steps 3 and 4): from the real number to the
 * source brand's archive, and from the target brand's archive back to the real number. Every update is by collection
 * name and matches only records still naming {@code from}, so running it again after a stop finishes what is left
 * and changes nothing else. {@code NumberRecordsCoverageTest} keeps the two lists complete.
 */
@Component
public class NumberRecords {

    private static final String WEBHOOK_NUMBER = "event.entry.changes.value.metadata.phone_number_id";
    private static final String MEDIA_FILES = ChatMediaConfig.BUCKET + ".files";

    /** Collections re-pointed, with the field that names the number. */
    public static final Map<String, String> REPOINTED = ordered(
            "waWebChats", "phoneNumberId",
            "waWebMessages", "phoneNumberId",
            "waWebHistoryImports", "phoneNumberId",
            "waWebHistoryImportChats", "phoneNumberId",
            "chatbotRuns", "phoneNumberId",
            "automationEvents", "data.number.id (queued events: a done one moves to automationEventHistory)",
            "messagePages", "phoneNumberId",
            "campaignSenders", "phoneNumberId",
            "campaigns", "senderIds (an element)",
            "campaignRecipients", "senderId, preferredSenderId",
            "channelWebhookEvents", WEBHOOK_NUMBER,
            MEDIA_FILES, "metadata.phoneNumberId",
            "crmContacts", "channel.phoneNumberId, origin.phoneNumberId, channelHistory[] (through CrmNumberRecords)",
            "crmDeals", "channel.phoneNumberId (through CrmNumberRecords)",
            "crmActivities", "phoneNumberId (through CrmNumberRecords)",
            "crmNotes", "phoneNumberId (through CrmNumberRecords)",
            "crmTasks", "phoneNumberId (through CrmNumberRecords)");

    /** Collections that name a number and stay as they are, with the reason. */
    public static final Map<String, String> LEFT = ordered(
            "phoneNumbers", "the number itself: it moves, and the archive is a new document",
            "connectCodes", "belong to the device, which stays with the number",
            "waNumberLookups", "per brand: each brand keeps its own lookups of WhatsApp ids",
            "inboxSettings", "legacy field, read only by its migration",
            "waWebChatActions", "keyed by chat id, no number field: they follow their chats",
            "phoneNumberMoves", "the move history names the real number on purpose",
            "automationEventHistory", "history: what the rules saw when the event ran, kept as it was",
            "automationTests", "rule test records: what a test ran on, kept as it was");

    private final MongoTemplate mongo;
    private final CrmNumberRecords crm;

    public NumberRecords(MongoTemplate mongo, CrmNumberRecords crm) {
        this.mongo = mongo;
        this.crm = crm;
    }

    /** Every record naming {@code fromPhoneNumberId} names {@code toPhoneNumberId} instead. Safe to run again. */
    public void repoint(String fromPhoneNumberId, String toPhoneNumberId) {
        String from = fromPhoneNumberId;
        String to = toPhoneNumberId;
        // documents saved whole elsewhere get a version bump, so a copy read before the move cannot be saved back
        set("waWebChats", "phoneNumberId", from, to, true);
        set("waWebMessages", "phoneNumberId", from, to, false);
        set("waWebHistoryImports", "phoneNumberId", from, to, true);
        set("waWebHistoryImportChats", "phoneNumberId", from, to, false);
        set("chatbotRuns", "phoneNumberId", from, to, true);
        // a queued event of the old brand then names the archive: its actions stay with the archived chat (a send is
        // refused) instead of reaching the number in its new brand
        set("automationEvents", "data.number.id", from, to, false);
        set("messagePages", "phoneNumberId", from, to, false);
        set("campaignSenders", "phoneNumberId", from, to, true);
        mongo.updateMulti(new Query(Criteria.where("senderIds").is(from)),
                new Update().set("senderIds.$", to).inc("version", 1), "campaigns");
        set("campaignRecipients", "senderId", from, to, false);
        set("campaignRecipients", "preferredSenderId", from, to, false);
        // array filters, so an entry or change of another shape is skipped rather than failing the update
        mongo.updateMulti(new Query(Criteria.where(WEBHOOK_NUMBER).is(from)),
                new Update().set("event.entry.$[e].changes.$[c].value.metadata.phone_number_id", to)
                        .filterArray(Criteria.where("e.changes.value.metadata.phone_number_id").is(from))
                        .filterArray(Criteria.where("c.value.metadata.phone_number_id").is(from)),
                "channelWebhookEvents");
        set(MEDIA_FILES, "metadata.phoneNumberId", from, to, false);
        crm.repoint(from, to);
    }

    private void set(String collection, String field, String from, String to, boolean bumpVersion) {
        Update u = new Update().set(field, to);
        if (bumpVersion) u.inc("version", 1);
        mongo.updateMulti(new Query(Criteria.where(field).is(from)), u, collection);
    }

    private static Map<String, String> ordered(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put(pairs[i], pairs[i + 1]);
        return Collections.unmodifiableMap(m);
    }
}
