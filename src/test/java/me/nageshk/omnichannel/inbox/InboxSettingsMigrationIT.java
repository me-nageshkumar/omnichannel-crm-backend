package me.nageshk.omnichannel.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import me.nageshk.omnichannel.support.TestUsers;
import me.nageshk.omnichannel.tenant.Customer;
import me.nageshk.omnichannel.tenant.Workspace;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/** Per-number settings documents from before design §3.4 become a brand default and are kept aside. */
@SpringBootTest
class InboxSettingsMigrationIT {

    @Autowired InboxSettingsMigration migration;
    @Autowired InboxSettingService settings;
    @Autowired TestUsers testUsers;
    @Autowired MongoTemplate mongo;

    Customer acme;
    Workspace brand;
    Workspace other;

    @BeforeEach
    void setUp() {
        clean();
        acme = testUsers.customer("Acme");
        brand = testUsers.workspace(acme.getId(), "Brand A");
        other = testUsers.workspace(acme.getId(), "Brand B");
        preMigrationIndexes();
    }

    @AfterEach
    void tearDown() {
        clean();
        migration.ensureIndex();
    }

    private void clean() {
        testUsers.deleteAll();
        mongo.remove(new Query(), "inboxSettings");
        mongo.remove(new Query(), "inboxSettingsLegacy");
    }

    /** The collection as an older deployment left it: per-number documents under the per-number unique index. */
    private void preMigrationIndexes() {
        IndexOperations ops = mongo.indexOps("inboxSettings");
        ops.getIndexInfo().stream().map(IndexInfo::getName).filter(n -> !n.equals("_id_")).forEach(ops::dropIndex);
        ops.createIndex(new Index().named("phone_unique").on("phoneNumberId", Sort.Direction.ASC).unique());
    }

    private Document legacy(Workspace w, String phoneNumberId, String mode, String teamId, String userId,
            boolean claimOnReply, boolean keepAssignmentOnClose) {
        Document d = new Document("customerId", acme.getId()).append("workspaceId", w.getId())
                .append("phoneNumberId", phoneNumberId)
                .append("routing", new Document("mode", mode).append("teamId", teamId).append("userId", userId))
                .append("claimOnReply", claimOnReply).append("keepAssignmentOnClose", keepAssignmentOnClose)
                .append("roundRobinCursor", "u-stale");
        mongo.insert(d, "inboxSettings");
        return d;
    }

    @Test
    void legacyDocumentsMoveToLegacyCollectionAndSeedTheDefault() {
        legacy(brand, "p1", "ROUND_ROBIN", "t1", null, false, true);

        migration.migrate();

        assertThat(mongo.count(new Query(Criteria.where("phoneNumberId").exists(true)), "inboxSettings")).isZero();
        assertThat(mongo.count(new Query(), "inboxSettingsLegacy")).isEqualTo(1);
        List<String> indexes = mongo.indexOps("inboxSettings").getIndexInfo().stream().map(IndexInfo::getName).toList();
        assertThat(indexes).contains("workspace_team_unique").doesNotContain("phone_unique");
        assertThat(migration.legacyFor("p1")).isPresent();
        assertThat(migration.legacyFor("p1").get().get("routing", Document.class).getString("teamId")).isEqualTo("t1");
        InboxSetting d = settings.brandDefault(brand.getId());
        assertThat(d.getRouting()).isEqualTo(new InboxSetting.Routing(RoutingMode.ROUND_ROBIN, null));
        assertThat(d.isClaimOnReply()).isFalse();
        assertThat(d.isKeepAssignmentOnClose()).isTrue();
        assertThat(d.getStickyDays()).isEqualTo(30);
        assertThat(d.getCustomerId()).isEqualTo(acme.getId());
    }

    @Test
    void migrationPicksMostCommonRoutingAndSurvivesStaleUsers() {
        legacy(brand, "p1", "TEAM", "t1", null, true, false);
        legacy(brand, "p2", "ROUND_ROBIN", "t1", null, true, false);
        legacy(brand, "p3", "USER", null, "gone-user", true, false);
        legacy(other, "p4", "NONE", null, null, true, false);

        migration.migrate();

        // USER counts as TEAM, so TEAM wins 2:1; brand B is NONE
        assertThat(settings.brandDefault(brand.getId()).getRouting().mode()).isEqualTo(RoutingMode.TEAM);
        assertThat(settings.brandDefault(other.getId()).getRouting().mode()).isEqualTo(RoutingMode.NONE);
        assertThat(mongo.count(new Query(), "inboxSettingsLegacy")).isEqualTo(4);
    }

    @Test
    void tieGoesToTeamAndAnExistingDefaultIsKept() {
        legacy(brand, "p1", "NONE", null, null, true, false);
        legacy(brand, "p2", "ROUND_ROBIN", "t1", null, true, false);
        legacy(other, "p3", "ROUND_ROBIN", "t2", null, true, false);
        settings.putDefault(other.getId(), new RoutingSetting.Request(false, false, null, 5, null, null));

        migration.migrate();

        assertThat(settings.brandDefault(brand.getId()).getRouting().mode()).isEqualTo(RoutingMode.TEAM);
        assertThat(settings.brandDefault(other.getId()).getRouting().mode()).isEqualTo(RoutingMode.NONE);
        assertThat(settings.brandDefault(other.getId()).getStickyDays()).isEqualTo(5);
    }

    @Test
    void secondRunIsNoop() {
        legacy(brand, "p1", "TEAM", "t1", null, true, false);
        migration.migrate();
        String defaultId = settings.brandDefault(brand.getId()).getId();

        migration.migrate();

        assertThat(mongo.count(new Query(), "inboxSettingsLegacy")).isEqualTo(1);
        assertThat(mongo.count(new Query(), "inboxSettings")).isEqualTo(1);
        assertThat(settings.brandDefault(brand.getId()).getId()).isEqualTo(defaultId);
        assertThat(migration.legacyFor("nope")).isEmpty();
        assertThat(List.of()).isEmpty();
    }
}
