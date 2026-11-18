package me.nageshk.omnichannel.app.phone;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import me.nageshk.omnichannel.db.phone.PhoneNumberIndexMigration;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.IndexOperations;

/** The plain unique index on {@code number} is replaced by one that ignores archives, which have no number. */
@SpringBootTest
class PhoneNumberIndexMigrationIT {

    @Autowired PhoneNumberIndexMigration migration;
    @Autowired MongoTemplate mongo;

    @AfterEach
    void tearDown() {
        migration.ensureIndex();
    }

    @Test
    void oldUniqueIndexIsReplacedByAPartialOne() {
        IndexOperations ops = mongo.indexOps("phoneNumbers");
        ops.getIndexInfo().stream().map(IndexInfo::getName).filter(n -> n.startsWith("number")).forEach(ops::dropIndex);
        ops.createIndex(new Index().named("number").on("number", Sort.Direction.ASC).unique());

        migration.ensureIndex();

        List<IndexInfo> indexes = mongo.indexOps("phoneNumbers").getIndexInfo();
        assertThat(indexes.stream().map(IndexInfo::getName)).contains("number_unique").doesNotContain("number");
        IndexInfo created = indexes.stream().filter(i -> i.getName().equals("number_unique")).findFirst().orElseThrow();
        assertThat(created.isUnique()).isTrue();
        assertThat(created.getPartialFilterExpression()).contains("$type");
        assertThat(Document.parse(created.getPartialFilterExpression()).get("number", Document.class).get("$type"))
                .isEqualTo("string");
    }

    @Test
    void runningTwiceIsHarmless() {
        migration.ensureIndex();
        migration.ensureIndex();
        assertThat(mongo.indexOps("phoneNumbers").getIndexInfo().stream()
                .filter(i -> i.getName().startsWith("number")).count()).isEqualTo(1);
    }
}
