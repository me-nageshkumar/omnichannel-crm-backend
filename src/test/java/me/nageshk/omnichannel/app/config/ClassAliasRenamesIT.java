package me.nageshk.omnichannel.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import me.nageshk.omnichannel.db.config.ClassAliasRenames;
import me.nageshk.omnichannel.db.contact.ContactFilter;
import me.nageshk.omnichannel.db.contact.ContactFilterRepository;
import me.nageshk.omnichannel.db.contact.ContactNote;
import me.nageshk.omnichannel.db.contact.advanced.Rule;
import me.nageshk.omnichannel.db.contact.advanced.RuleGroup;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

@SpringBootTest
class ClassAliasRenamesIT {

    private static final String OLD = "me.nageshk.omnichannel.";

    @Autowired MongoTemplate mongo;
    @Autowired ClassAliasRenames aliases;
    @Autowired ContactFilterRepository filters;

    @BeforeEach
    @AfterEach
    void clean() {
        mongo.remove(new Query(), ContactNote.class);
        mongo.remove(new Query(), ContactFilter.class);
    }

    private String storedClass(String collection, ObjectId id) {
        return mongo.getCollection(collection).find(new Document("_id", id)).first().getString("_class");
    }

    private static Document rule(String field, String value) {
        return new Document("field", field).append("operator", "equals").append("value", value)
                .append("_class", OLD + "contact.advanced.Rule");
    }

    @Test
    void rewritesTopLevelHintsFromTheOldPackage() {
        ObjectId id = new ObjectId();
        mongo.getCollection("contactNotes").insertOne(new Document("_id", id).append("workspaceId", "w1")
                .append("text", "hello").append("_class", OLD + "contact.ContactNote"));

        aliases.migrate();

        assertThat(storedClass("contactNotes", id)).isEqualTo(ContactNote.class.getName());
    }

    @Test
    void rewritesTheRuleTreeOfAnAdvancedFilterSoItLoads() {
        ObjectId id = new ObjectId();
        Document nested = new Document("combinator", "or").append("not", false)
                .append("rules", List.of(rule("name", "b"))).append("_class", OLD + "contact.advanced.RuleGroup");
        mongo.getCollection("contactFilters").insertOne(new Document("_id", id).append("workspaceId", "w1")
                .append("name", "Old").append("nameKey", "old").append("kind", "ADVANCED")
                .append("rules", new Document("combinator", "and").append("not", false)
                        .append("rules", List.of(rule("name", "a"), nested)))
                .append("_class", OLD + "contact.ContactFilter"));

        aliases.migrate();

        assertThat(storedClass("contactFilters", id)).isEqualTo(ContactFilter.class.getName());
        RuleGroup rules = filters.findById(id.toHexString()).orElseThrow().getRules();
        assertThat(rules.rules()).hasSize(2);
        assertThat(rules.rules().get(0)).isEqualTo(new Rule("name", "equals", "a"));
        RuleGroup group = (RuleGroup) rules.rules().get(1);
        assertThat(group.combinator()).isEqualTo("or");
        assertThat(group.rules()).containsExactly(new Rule("name", "equals", "b"));
    }

    @Test
    void aSecondRunChangesNothing() {
        mongo.getCollection("contactNotes").insertOne(new Document("_id", new ObjectId()).append("workspaceId", "w1")
                .append("text", "hello").append("_class", OLD + "contact.ContactNote"));

        assertThat(aliases.migrate()).isEqualTo(1);
        assertThat(aliases.migrate()).isZero();
    }

    @Test
    void leavesCurrentAndForeignHintsAlone() {
        ObjectId current = new ObjectId();
        ObjectId foreign = new ObjectId();
        mongo.getCollection("contactNotes").insertMany(List.of(
                new Document("_id", current).append("workspaceId", "w1").append("_class", ContactNote.class.getName()),
                new Document("_id", foreign).append("workspaceId", "w1").append("_class", "com.example.Note")));

        assertThat(aliases.migrate()).isZero();

        assertThat(storedClass("contactNotes", current)).isEqualTo(ContactNote.class.getName());
        assertThat(storedClass("contactNotes", foreign)).isEqualTo("com.example.Note");
    }
}
