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

    private ObjectId insertAdvancedFilter(Document rules) {
        ObjectId id = new ObjectId();
        mongo.getCollection("contactFilters").insertOne(new Document("_id", id).append("workspaceId", "w1")
                .append("name", "Old " + id).append("nameKey", "old " + id).append("kind", "ADVANCED")
                .append("rules", rules).append("_class", OLD + "contact.ContactFilter"));
        return id;
    }

    @Test
    void leavesTopLevelHintsAloneBecauseTheyStillLoad() {
        ObjectId id = new ObjectId();
        mongo.getCollection("contactNotes").insertOne(new Document("_id", id).append("workspaceId", "w1")
                .append("text", "hello").append("_class", OLD + "contact.ContactNote"));

        assertThat(aliases.migrate()).isZero();

        assertThat(storedClass("contactNotes", id)).isEqualTo(OLD + "contact.ContactNote");
        assertThat(mongo.findById(id, ContactNote.class).getText()).isEqualTo("hello");
    }

    @Test
    void rewritesTheRuleTreeOfAnAdvancedFilterSoItLoads() {
        Document nested = new Document("combinator", "or").append("not", false)
                .append("rules", List.of(rule("name", "b"))).append("_class", OLD + "contact.advanced.RuleGroup");
        ObjectId id = insertAdvancedFilter(new Document("combinator", "and").append("not", false)
                .append("rules", List.of(rule("name", "a"), nested)));

        assertThat(aliases.migrate()).isEqualTo(1);

        RuleGroup rules = filters.findById(id.toHexString()).orElseThrow().getRules();
        assertThat(rules.rules()).hasSize(2);
        assertThat(rules.rules().get(0)).isEqualTo(new Rule("name", "equals", "a"));
        RuleGroup group = (RuleGroup) rules.rules().get(1);
        assertThat(group.combinator()).isEqualTo("or");
        assertThat(group.rules()).containsExactly(new Rule("name", "equals", "b"));
    }

    @Test
    void aSecondRunChangesNothing() {
        insertAdvancedFilter(new Document("combinator", "and").append("not", false)
                .append("rules", List.of(rule("name", "a"))));

        assertThat(aliases.migrate()).isEqualTo(1);
        assertThat(aliases.migrate()).isZero();
    }

    @Test
    void leavesCurrentAndForeignNestedHintsAlone() {
        ObjectId id = insertAdvancedFilter(new Document("combinator", "and").append("not", false)
                .append("rules", List.of(
                        new Document("field", "name").append("operator", "equals").append("value", "a")
                                .append("_class", Rule.class.getName()),
                        new Document("field", "name").append("operator", "equals").append("value", "b")
                                .append("_class", "com.example.Rule"))));

        assertThat(aliases.migrate()).isZero();

        List<Document> stored = mongo.getCollection("contactFilters").find(new Document("_id", id)).first()
                .get("rules", Document.class).getList("rules", Document.class);
        assertThat(stored).extracting(d -> d.getString("_class"))
                .containsExactly(Rule.class.getName(), "com.example.Rule");
    }
}
