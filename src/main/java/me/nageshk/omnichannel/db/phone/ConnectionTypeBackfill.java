package me.nageshk.omnichannel.db.phone;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/** Marks numbers saved before {@code connectionType} existed as WhatsApp Web. Idempotent; every profile. */
@Component
@Order(7)
public class ConnectionTypeBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ConnectionTypeBackfill.class);

    private final MongoTemplate mongo;

    public ConnectionTypeBackfill(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public void run(ApplicationArguments args) {
        long n = mongo.updateMulti(new Query(Criteria.where("connectionType").exists(false)),
                new Update().set("connectionType", ConnectionType.WHATSAPP_WEB.name()), PhoneNumber.class)
                .getModifiedCount();
        if (n > 0) log.info("marked {} phone number(s) as {}", n, ConnectionType.WHATSAPP_WEB);
    }
}
