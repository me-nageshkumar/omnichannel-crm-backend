package me.nageshk.omnichannel.app.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.common.config.OmnichannelProperties;
import me.nageshk.omnichannel.db.access.RoleRepository;
import me.nageshk.omnichannel.db.automation.AutomationEvent;
import me.nageshk.omnichannel.db.automation.AutomationEventKind;
import me.nageshk.omnichannel.db.automation.AutomationEventRecord;
import me.nageshk.omnichannel.db.automation.AutomationRule;
import me.nageshk.omnichannel.db.automation.AutomationRun;
import me.nageshk.omnichannel.db.automation.AutomationTest;
import me.nageshk.omnichannel.db.automation.ConditionNode;
import me.nageshk.omnichannel.security.access.BuiltInRoles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

@SpringBootTest
class AutomationModelIT {

    private static final List<Class<?>> DOCS = List.of(AutomationRule.class, AutomationEvent.class,
            AutomationEventRecord.class, AutomationRun.class, AutomationTest.class);

    @Autowired MongoTemplate mongo;
    @Autowired OmnichannelProperties props;
    @Autowired RoleRepository roles;

    @BeforeEach
    @AfterEach
    void clean() {
        DOCS.forEach(c -> mongo.remove(new Query(), c));
    }

    @Test
    void collectionsAreCamelCase() {
        assertThat(DOCS.stream().map(mongo::getCollectionName).toList()).containsExactly("automationRules",
                "automationEvents", "automationEventHistory", "automationRuns", "automationTests");
    }

    @Test
    void noCollectionHasATtlIndex() {
        for (Class<?> c : DOCS) {
            assertThat(mongo.indexOps(c).getIndexInfo()).allSatisfy(i -> assertThat(i.getExpireAfter()).isEmpty());
        }
    }

    private AutomationRun run(String eventId, String ruleId) {
        AutomationRun r = new AutomationRun();
        r.setEventId(eventId);
        r.setRuleId(ruleId);
        return r;
    }

    @Test
    void runIsUniquePerEventAndRule() {
        mongo.insert(run("e1", "r1"));
        mongo.insert(run("e1", "r2"));
        assertThatThrownBy(() -> mongo.insert(run("e1", "r1"))).isInstanceOf(DuplicateKeyException.class);
    }

    private AutomationTest test(String ruleId, boolean active) {
        AutomationTest t = new AutomationTest();
        t.setRuleId(ruleId);
        t.setActive(active);
        return t;
    }

    @Test
    void oneActiveTestPerRule() {
        mongo.insert(test("r1", true));
        assertThatThrownBy(() -> mongo.insert(test("r1", true))).isInstanceOf(DuplicateKeyException.class);
        mongo.insert(test("r2", false));
        mongo.insert(test("r2", false));
        mongo.insert(test("r2", true));
        assertThat(mongo.count(new Query(), AutomationTest.class)).isEqualTo(4);
    }

    @Test
    void conditionTreeRoundTrips() {
        ConditionNode tree = new ConditionNode("and", false, List.of(
                new ConditionNode(null, null, null, "message.type", "is_one_of", List.of("TEXT", "PHOTO")),
                new ConditionNode("or", null, List.of(
                        new ConditionNode(null, null, null, "contact.inCrm", "is", false)), null, null, null)),
                null, null, null);
        AutomationRule rule = new AutomationRule();
        rule.setWorkspaceId("w1");
        rule.setEvent(AutomationEventKind.MESSAGE_RECEIVED);
        rule.setConditions(tree);
        String id = mongo.save(rule).getId();
        AutomationRule back = mongo.findById(id, AutomationRule.class);
        assertThat(back.getConditions()).isEqualTo(tree);
        assertThat(back.getConditions().isGroup()).isTrue();
        assertThat(back.getConditions().rules().get(0).isGroup()).isFalse();
    }

    @Test
    void propertiesHaveDefaults() {
        var a = props.automation();
        assertThat(a.lease()).isEqualTo(Duration.ofMinutes(5));
        assertThat(a.maxAttempts()).isEqualTo(3);
        assertThat(a.testIdle()).isEqualTo(Duration.ofMinutes(30));
        assertThat(a.autoTick()).isFalse();
        assertThat(a.tick()).isEqualTo(Duration.ofSeconds(2));
        assertThat(a.batch()).isEqualTo(20);
    }

    @Test
    void authoritiesSeededToManagers() {
        for (String name : List.of(BuiltInRoles.WORKSPACE_MANAGER, BuiltInRoles.CUSTOMER_ADMIN))
            assertThat(roles.findByName(name).orElseThrow().getAuthorities())
                    .contains("automation:view", "automation:manage");
        assertThat(roles.findByName(BuiltInRoles.AGENT).orElseThrow().getAuthorities())
                .doesNotContain("automation:view", "automation:manage");
    }
}
