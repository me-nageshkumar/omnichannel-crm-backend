package me.nageshk.omnichannel.service.automation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import me.nageshk.omnichannel.db.automation.AutomationRule;
import org.junit.jupiter.api.Test;

class RuleOrderTest {

    private static AutomationRule rule(String id, Integer priority, Instant created) {
        AutomationRule r = new AutomationRule();
        r.setId(id);
        r.setPriority(priority);
        r.setCreationTime(created);
        return r;
    }

    @Test
    void ruleOrderIsPriorityThenAgeThenId() {
        Instant t = Instant.parse("2026-11-03T00:00:00Z");
        List<AutomationRule> rules = new ArrayList<>(List.of(rule("a", null, t), rule("b", 5, t.plusSeconds(1)),
                rule("c", 10, t.plusSeconds(2)), rule("d", null, t.plusSeconds(3)), rule("e", 5, t.plusSeconds(4)),
                rule("g", 1, t.plusSeconds(9)), rule("f", 1, t.plusSeconds(9))));
        rules.sort(RuleOrder.COMPARATOR);
        assertThat(rules).extracting(AutomationRule::getId).containsExactly("c", "b", "e", "f", "g", "a", "d");
    }
}
