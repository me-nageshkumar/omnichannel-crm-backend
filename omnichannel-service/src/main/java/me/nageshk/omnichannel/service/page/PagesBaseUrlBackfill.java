package me.nageshk.omnichannel.service.page;

import com.mongodb.client.MongoCollection;
import java.time.Instant;
import me.nageshk.omnichannel.db.chatbot.ChatbotForm;
import me.nageshk.omnichannel.db.page.MessagePage;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Once: message pages and forms from before link addresses get the platform address they were sent with (spec
 * 2026-11-18 link addresses §5.3), so a later change of the platform address cannot move an old page.
 */
@Component
public class PagesBaseUrlBackfill implements ApplicationRunner {

    public static final String MARKER_ID = "pagesBaseUrl";
    private static final String MARKERS = "dataMigrations";
    private static final Logger log = LoggerFactory.getLogger(PagesBaseUrlBackfill.class);

    private final MongoTemplate mongo;
    private final PagesAddresses addresses;

    public PagesBaseUrlBackfill(MongoTemplate mongo, PagesAddresses addresses) {
        this.mongo = mongo;
        this.addresses = addresses;
    }

    @Override
    public void run(ApplicationArguments args) {
        MongoCollection<Document> markers = mongo.getCollection(MARKERS);
        if (markers.find(new Document("_id", MARKER_ID)).first() != null) return;
        String base = addresses.platform();
        Query without = new Query(new Criteria().orOperator(Criteria.where("baseUrl").exists(false),
                Criteria.where("baseUrl").is(null)));
        long pages = mongo.updateMulti(without, new Update().set("baseUrl", base), MessagePage.class).getModifiedCount();
        long forms = mongo.updateMulti(without, new Update().set("baseUrl", base), ChatbotForm.class).getModifiedCount();
        try {
            markers.insertOne(new Document("_id", MARKER_ID).append("appliedAt", Instant.now()));
        } catch (com.mongodb.MongoWriteException raced) {
            // another instance finished first
        }
        if (pages + forms > 0) log.info("stamped {} message page(s) and {} form(s) with the platform link address", pages, forms);
    }
}
