package me.nageshk.omnichannel.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import me.nageshk.omnichannel.db.campaign.CampaignCsvConfig;
import me.nageshk.omnichannel.db.config.CollectionRenames;
import me.nageshk.omnichannel.db.contact.ContactNote;
import me.nageshk.omnichannel.db.inbox.ChatMediaConfig;
import me.nageshk.omnichannel.db.template.TemplateMediaConfig;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.mapping.MongoPersistentEntity;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;

@SpringBootTest
class CollectionRenamesIT {

    @Autowired MongoTemplate mongo;
    @Autowired MongoConverter converter;
    @Autowired CollectionRenames renames;
    @Autowired @Qualifier("chatMediaGridFs") GridFsTemplate chatMedia;

    /** Collections a test may create under an old name, dropped afterwards. */
    private final List<String> created = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void clean() {
        for (String c : created) mongo.dropCollection(c);
        mongo.dropCollection("contact_notes");
        mongo.dropCollection("chat_media.files");
        mongo.dropCollection("chat_media.chunks");
        mongo.dropCollection("system_state");
        mongo.remove(new Query(), ContactNote.class);
        mongo.remove(new Query(), "chatMedia.files");
        mongo.remove(new Query(), "chatMedia.chunks");
    }

    private void insert(String collection, Document... docs) {
        mongo.getCollection(collection).insertMany(List.of(docs));
    }

    private static Document doc(String text) {
        return new Document("_id", new ObjectId()).append("workspaceId", "w1").append("text", text);
    }

    private List<String> indexNames(String collection) {
        return mongo.indexOps(collection).getIndexInfo().stream().map(IndexInfo::getName).toList();
    }

    @Test
    void renamesAnOldCollectionKeepingItsDocumentsAndIndexes() {
        insert("contact_notes", doc("first"), doc("second"));
        mongo.getCollection("contact_notes").createIndex(new Document("text", 1),
                new com.mongodb.client.model.IndexOptions().name("legacy_text"));
        List<String> codeIndexes = indexNames("contactNotes");

        renames.migrate();

        assertThat(mongo.collectionExists("contact_notes")).isFalse();
        assertThat(mongo.getCollection("contactNotes").countDocuments()).isEqualTo(2);
        assertThat(indexNames("contactNotes")).contains("legacy_text").containsAll(codeIndexes);
    }

    @Test
    void bucketsMoveFilesAndChunksAndStayReadable() throws Exception {
        GridFsTemplate old = new GridFsTemplate(mongo.getMongoDatabaseFactory(), converter, "chat_media");
        ObjectId id = old.store(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)), "hello.txt");

        renames.migrate();

        assertThat(mongo.collectionExists("chat_media.files")).isFalse();
        assertThat(mongo.collectionExists("chat_media.chunks")).isFalse();
        var file = chatMedia.findOne(Query.query(org.springframework.data.mongodb.core.query.Criteria.where("_id").is(id)));
        assertThat(file).isNotNull();
        try (var in = chatMedia.getResource(file).getInputStream()) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello");
        }
    }

    @Test
    void bothNonEmptyIsAConflictAndNothingMoves() {
        insert("contact_notes", doc("old"));
        insert("contactNotes", doc("new"));

        renames.migrate();

        assertThat(mongo.getCollection("contact_notes").countDocuments()).isEqualTo(1);
        assertThat(mongo.getCollection("contactNotes").countDocuments()).isEqualTo(1);
        assertThat(mongo.getCollection("contactNotes").find().first().getString("text")).isEqualTo("new");
    }

    @Test
    void anEmptyOldLeftoverIsDropped() {
        mongo.createCollection("contact_notes");
        insert("contactNotes", doc("new"));

        renames.migrate();

        assertThat(mongo.collectionExists("contact_notes")).isFalse();
        assertThat(mongo.getCollection("contactNotes").countDocuments()).isEqualTo(1);
    }

    @Test
    void runningTwiceChangesNothing() {
        insert("contact_notes", doc("first"));
        renames.migrate();

        renames.migrate();

        assertThat(mongo.collectionExists("contact_notes")).isFalse();
        assertThat(mongo.getCollection("contactNotes").countDocuments()).isEqualTo(1);
    }

    @Test
    void anEmptyLegacySystemStateIsDroppedAndAFullOneIsKept() {
        mongo.createCollection("system_state");
        renames.migrate();
        assertThat(mongo.collectionExists("system_state")).isFalse();

        insert("system_state", new Document("_id", "known-authorities"));
        renames.migrate();
        assertThat(mongo.collectionExists("system_state")).isTrue();
    }

    @Test
    void everyMappedCollectionAndBucketIsCamelCase() {
        List<String> names = new ArrayList<>();
        for (MongoPersistentEntity<?> e : mongo.getConverter().getMappingContext().getPersistentEntities())
            if (e.isAnnotationPresent(org.springframework.data.mongodb.core.mapping.Document.class))
                names.add(e.getCollection());
        names.addAll(List.of(ChatMediaConfig.BUCKET, TemplateMediaConfig.BUCKET, CampaignCsvConfig.BUCKET));

        assertThat(names).isNotEmpty().allSatisfy(n -> assertThat(n).doesNotContain("_"));
    }

    @Test
    void everyOldNameMapsToANameTheCodeUses() {
        List<String> used = new ArrayList<>();
        for (MongoPersistentEntity<?> e : mongo.getConverter().getMappingContext().getPersistentEntities())
            used.add(e.getCollection());
        for (String bucket : List.of(ChatMediaConfig.BUCKET, TemplateMediaConfig.BUCKET, CampaignCsvConfig.BUCKET)) {
            used.add(bucket + ".files");
            used.add(bucket + ".chunks");
        }

        assertThat(CollectionRenames.RENAMES).hasSize(25);
        assertThat(used).containsAll(CollectionRenames.RENAMES.values());
        assertThat(CollectionRenames.RENAMES.keySet()).allSatisfy(old -> assertThat(old).contains("_"));
    }
}
