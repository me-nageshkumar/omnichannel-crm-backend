package me.nageshk.omnichannel.app.page;

import static org.assertj.core.api.Assertions.assertThat;

import me.nageshk.omnichannel.db.chatbot.ChatbotForm;
import me.nageshk.omnichannel.db.page.MessagePage;
import me.nageshk.omnichannel.db.page.PagesSettingsRepository;
import me.nageshk.omnichannel.service.page.PagesBaseUrlBackfill;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/** Link addresses spec §5.3: pages and forms from before carry the platform address they were sent with. */
@SpringBootTest
@me.nageshk.omnichannel.app.support.OwnDatabase
class PagesBaseUrlBackfillIT {

    @Autowired PagesBaseUrlBackfill backfill;
    @Autowired MongoTemplate mongo;
    @Autowired PagesSettingsRepository settings;

    @BeforeEach
    void clean() {
        mongo.remove(new Query(), MessagePage.class);
        mongo.remove(new Query(), ChatbotForm.class);
        mongo.remove(new Query(Criteria.where("_id").is(PagesBaseUrlBackfill.MARKER_ID)), "dataMigrations");
        settings.deleteAll();
    }

    @AfterEach
    void after() {
        clean();
    }

    private String page(String token, String baseUrl) {
        Document d = new Document("token", token).append("workspaceId", "w1");
        if (baseUrl != null) d.append("baseUrl", baseUrl);
        mongo.getCollection(mongo.getCollectionName(MessagePage.class)).insertOne(d);
        return d.getObjectId("_id").toHexString();
    }

    private String baseOf(String id) {
        return mongo.getCollection(mongo.getCollectionName(MessagePage.class))
                .find(new Document("_id", new org.bson.types.ObjectId(id))).first().getString("baseUrl");
    }

    @Test
    void oldRowsGetThePlatformAddressOnce() {
        String old = page("t-old", null);
        String sent = page("t-new", "https://acme.in");
        mongo.getCollection(mongo.getCollectionName(ChatbotForm.class)).insertOne(new Document("token", "f-old"));

        backfill.run(null);

        assertThat(baseOf(old)).isEqualTo("http://pages.test");
        assertThat(baseOf(sent)).isEqualTo("https://acme.in");
        assertThat(mongo.getCollection(mongo.getCollectionName(ChatbotForm.class)).find(new Document("token", "f-old"))
                .first().getString("baseUrl")).isEqualTo("http://pages.test");

        // a later run does nothing: the marker is there
        mongo.updateFirst(new Query(Criteria.where("token").is("t-old")), new Update().unset("baseUrl"), MessagePage.class);
        backfill.run(null);
        assertThat(baseOf(old)).isNull();
    }
}
