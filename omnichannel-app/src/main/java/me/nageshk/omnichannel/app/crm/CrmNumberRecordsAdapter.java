package me.nageshk.omnichannel.app.crm;

import java.util.List;
import me.nageshk.omnichannel.service.phone.move.CrmNumberRecords;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * A moved number's CRM records follow its old brand's chats onto the archive (spec §7.2 step 3), and back when the
 * number returns. By collection name, as {@code ChannelService} re-points follow-ups; contacts and deals get a version
 * bump as on a channel change, activities, notes and tasks keep theirs (Ruling B4).
 */
@Component
class CrmNumberRecordsAdapter implements CrmNumberRecords {

    private static final List<String> FOLLOW_UPS = List.of("crmActivities", "crmNotes", "crmTasks");

    private final MongoTemplate mongo;

    CrmNumberRecordsAdapter(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public void repoint(String from, String to) {
        set("crmContacts", "channel.phoneNumberId", from, to, true);
        set("crmContacts", "origin.phoneNumberId", from, to, true);
        for (String side : List.of("fromPhoneNumberId", "toPhoneNumberId")) {
            mongo.updateMulti(new Query(Criteria.where("channelHistory." + side).is(from)),
                    new Update().set("channelHistory.$[h]." + side, to).filterArray(Criteria.where("h." + side).is(from))
                            .inc("version", 1),
                    "crmContacts");
        }
        set("crmDeals", "channel.phoneNumberId", from, to, true);
        for (String c : FOLLOW_UPS) set(c, "phoneNumberId", from, to, false);
    }

    private void set(String collection, String field, String from, String to, boolean bumpVersion) {
        Update u = new Update().set(field, to);
        if (bumpVersion) u.inc("version", 1);
        mongo.updateMulti(new Query(Criteria.where(field).is(from)), u, collection);
    }
}
