package me.nageshk.omnichannel.service.automation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.db.automation.AssignMode;
import me.nageshk.omnichannel.db.automation.AutomationAction;
import me.nageshk.omnichannel.db.automation.AutomationActionType;
import me.nageshk.omnichannel.db.automation.AutomationEventKind;
import me.nageshk.omnichannel.db.automation.ConditionNode;
import org.junit.jupiter.api.Test;

class RuleShapeValidatorTest {

    private static final AutomationAction SEND = new AutomationAction(AutomationActionType.SEND_TEMPLATE, "t", null,
            null, null, null, null, null, null, null);

    private static ConditionNode leaf(String field, String op, Object value) {
        return new ConditionNode(null, null, null, field, op, value);
    }

    private static ConditionNode group(String combinator, ConditionNode... rules) {
        return new ConditionNode(combinator, null, List.of(rules), null, null, null);
    }

    private static RuleRequest rule(AutomationEventKind event, ConditionNode conditions,
            List<AutomationAction> actions) {
        return new RuleRequest("Rule", null, event, 10, conditions, actions, null);
    }

    private static Map<String, String> check(ConditionNode c) {
        return RuleShapeValidator.check(rule(AutomationEventKind.MESSAGE_RECEIVED, c, List.of(SEND)));
    }

    @Test
    void validRulePasses() {
        assertThat(check(group("and", leaf("message.text", "contains", "hi"), leaf("chat.new", "is", true))))
                .isEmpty();
        assertThat(RuleShapeValidator.check(rule(AutomationEventKind.DEAL_LANE_CHANGED,
                group("and", leaf("deal.amount", "between", List.of(1, 2)), leaf("toLane.type", "is", "WON")),
                List.of(SEND)))).isEmpty();
    }

    @Test
    void nameRequiredAndLength() {
        assertThat(RuleShapeValidator.check(new RuleRequest(" ", null, AutomationEventKind.MESSAGE_RECEIVED, null,
                null, List.of(), null))).containsEntry("name", "is required");
        assertThat(RuleShapeValidator.check(new RuleRequest("x".repeat(101), "d".repeat(501),
                AutomationEventKind.MESSAGE_RECEIVED, null, null, List.of(), null)))
                .containsEntry("name", "must be at most 100 characters")
                .containsEntry("description", "must be at most 500 characters");
    }

    @Test
    void eventRequired() {
        assertThat(RuleShapeValidator.check(new RuleRequest("n", null, null, null, null, List.of(), null)))
                .containsEntry("event", "is required");
    }

    @Test
    void priorityOutOfRange() {
        assertThat(RuleShapeValidator.check(new RuleRequest("n", null, AutomationEventKind.MESSAGE_RECEIVED, 10000,
                null, List.of(), null))).containsEntry("priority", "must be a whole number from 0 to 9999");
        assertThat(RuleShapeValidator.check(new RuleRequest("n", null, AutomationEventKind.MESSAGE_RECEIVED, -1,
                null, List.of(), null))).containsEntry("priority", "must be a whole number from 0 to 9999");
    }

    @Test
    void unknownFieldForEvent() {
        assertThat(check(group("and", leaf("deal.amount", "is_empty", null))))
                .containsEntry("conditions.rules[0].field", "is not a field of this event");
    }

    @Test
    void operatorNotOfferedForField() {
        assertThat(check(group("and", leaf("chat.new", "contains", "x"))))
                .containsEntry("conditions.rules[0].operator", "is not offered for this field");
    }

    @Test
    void valueChecks() {
        assertThat(check(group("and", leaf("message.text", "contains", null))))
                .containsEntry("conditions.rules[0].value", "is required");
        assertThat(check(group("and", leaf("chat.new", "is", "maybe"))))
                .containsEntry("conditions.rules[0].value", "must be true or false");
        assertThat(check(group("and", leaf("message.type", "is_one_of", List.of()))))
                .containsEntry("conditions.rules[0].value", "choose at least one");
        Map<String, String> deal = RuleShapeValidator.check(rule(AutomationEventKind.DEAL_LANE_CHANGED,
                group("and", leaf("deal.amount", "greater_than", "abc"),
                        leaf("deal.amount", "between", List.of(1))),
                List.of(SEND)));
        assertThat(deal).containsEntry("conditions.rules[0].value", "must be a number")
                .containsEntry("conditions.rules[1].value", "must be two numbers, low then high");
    }

    @Test
    void groupCombinator() {
        assertThat(check(group("xor", leaf("chat.new", "is", true))))
                .containsEntry("conditions.combinator", "must be and or or");
    }

    @Test
    void fourLevelsRefused() {
        ConditionNode l = leaf("chat.new", "is", true);
        assertThat(check(group("and", group("and", group("and", l))))).isEmpty();
        assertThat(check(group("and", group("and", group("and", group("and", l))))))
                .containsEntry("conditions", "at most 3 levels of groups");
    }

    @Test
    void thirtyOneConditionsRefused() {
        List<ConditionNode> leaves = new ArrayList<>();
        for (int i = 0; i < 31; i++) {
            leaves.add(leaf("chat.new", "is", true));
        }
        ConditionNode root = new ConditionNode("and", null, leaves, null, null, null);
        assertThat(check(root)).containsEntry("conditions", "at most 30 conditions");
        assertThat(check(new ConditionNode("and", null, leaves.subList(0, 30), null, null, null))).isEmpty();
    }

    @Test
    void actionChecks() {
        AutomationAction noType = new AutomationAction(null, null, null, null, null, null, null, null, null, null);
        AutomationAction assign = new AutomationAction(AutomationActionType.ASSIGN_CHAT, null, null, null, null,
                null, null, null, null, null);
        AutomationAction longTitle = new AutomationAction(AutomationActionType.ADD_TO_CRM, null, null, null, null,
                null, null, "p", "s", "t".repeat(201));
        Map<String, String> r = RuleShapeValidator.check(rule(AutomationEventKind.MESSAGE_RECEIVED, null,
                List.of(SEND, noType, assign, longTitle)));
        assertThat(r).containsEntry("actions[1].type", "is required")
                .containsEntry("actions[2].mode", "is required")
                .containsEntry("actions[3].dealTitle", "must be at most 200 characters")
                .doesNotContainKey("actions[0].type");
    }

    @Test
    void assignWithModeIsFine() {
        AutomationAction assign = new AutomationAction(AutomationActionType.ASSIGN_CHAT, null, null,
                AssignMode.values()[0], null, null, null, null, null, null);
        assertThat(RuleShapeValidator.check(rule(AutomationEventKind.MESSAGE_RECEIVED, null, List.of(assign))))
                .isEmpty();
    }

    @Test
    void addToCrmOnLaneChangeRefused() {
        AutomationAction add = new AutomationAction(AutomationActionType.ADD_TO_CRM, null, null, null, null, null,
                null, null, null, null);
        assertThat(RuleShapeValidator.check(rule(AutomationEventKind.DEAL_LANE_CHANGED, null, List.of(add))))
                .containsEntry("actions[0].type", "Add to CRM works on incoming messages only");
    }
}
