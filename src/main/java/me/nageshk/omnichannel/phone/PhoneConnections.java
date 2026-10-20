package me.nageshk.omnichannel.phone;

import java.time.Clock;
import java.time.Instant;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Connection facts written by the WebSocket layer. Field updates, not whole-document saves, so they
 * never clash with the optimistic lock of console edits.
 */
@Service
public class PhoneConnections {

    private final MongoTemplate mongo;
    private final Clock clock;

    public PhoneConnections(MongoTemplate mongo, Clock clock) {
        this.mongo = mongo;
        this.clock = clock;
    }

    public void connected(String phoneNumberId) {
        Instant now = Instant.now(clock);
        set(phoneNumberId, new Update().set("lastConnectedAt", now).set("lastSeenAt", now));
    }

    public void seen(String phoneNumberId) {
        set(phoneNumberId, new Update().set("lastSeenAt", Instant.now(clock)));
    }

    public void wrongNumber(String phoneNumberId, String reported) {
        set(phoneNumberId, new Update().set("lastWrongNumber", reported).set("lastWrongNumberAt", Instant.now(clock)));
    }

    private void set(String id, Update update) {
        mongo.updateFirst(new Query(Criteria.where("_id").is(id)), update, PhoneNumber.class);
    }
}
