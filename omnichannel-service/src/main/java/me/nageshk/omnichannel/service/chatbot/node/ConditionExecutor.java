package me.nageshk.omnichannel.service.chatbot.node;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import me.nageshk.omnichannel.db.automation.ConditionNode;
import me.nageshk.omnichannel.service.automation.ConditionEvaluator;
import me.nageshk.omnichannel.service.automation.Operator;
import me.nageshk.omnichannel.service.chatbot.run.RunVariables;
import me.nageshk.omnichannel.service.chatbot.flow.Flow;
import me.nageshk.omnichannel.service.chatbot.flow.FlowNode;
import me.nageshk.omnichannel.service.chatbot.flow.FlowParser;
import me.nageshk.omnichannel.service.chatbot.flow.NodeType;
import me.nageshk.omnichannel.service.chatbot.flow.Outputs;
import me.nageshk.omnichannel.service.chatbot.flow.settings.ConditionSettings;
import org.springframework.stereotype.Component;

/** Condition (spec §4.2): ordered cases over the run's variables; the first that matches wins, else otherwise. */
@Component
public class ConditionExecutor extends BaseExecutor {

    public ConditionExecutor(FlowParser parser) {
        super(parser);
    }

    @Override
    public NodeType type() {
        return NodeType.CONDITION;
    }

    @Override
    public List<String> outputs(FlowNode node, ValidationContext ctx) {
        ConditionSettings s = settings(node, ConditionSettings.class);
        List<String> out = new ArrayList<>();
        if (s.cases() != null) for (ConditionSettings.Case c : s.cases()) if (c != null && c.id() != null) out.add(Outputs.caseOf(c.id()));
        out.add(Outputs.OTHERWISE);
        return out;
    }

    @Override
    public StepResult run(RunContext ctx, FlowNode node) {
        ConditionSettings s = settings(node, ConditionSettings.class);
        Map<String, Object> flat = RunVariables.flatten(ctx.vars());
        if (s.cases() != null) {
            for (ConditionSettings.Case c : s.cases()) {
                if (c == null || c.conditions() == null) continue;
                if (ConditionEvaluator.matches(normalise(c.conditions(), ctx), flat)) {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("matched", c.label() == null ? c.id() : c.label());
                    return StepResult.next(Outputs.caseOf(c.id()), result, "Case “" + result.get("matched") + "”");
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("matched", null);
        return StepResult.next(Outputs.OTHERWISE, result, "No case matched");
    }

    /** Fields are written as {@code {{path}}}: strip the braces; values may hold variables: render them. */
    private static ConditionNode normalise(ConditionNode n, RunContext ctx) {
        if (n.isGroup()) {
            return new ConditionNode(n.combinator(), n.not(), n.rules().stream().map(r -> normalise(r, ctx)).toList(),
                    null, null, null);
        }
        String field = n.field() == null ? "" : n.field().strip();
        if (field.startsWith("{{") && field.endsWith("}}")) field = field.substring(2, field.length() - 2).strip();
        Object value = n.value();
        if (value instanceof String str && str.contains("{{")) value = ctx.render(str);
        if (value instanceof List<?> l) {
            value = l.stream().map(v -> v instanceof String str && str.contains("{{") ? ctx.render(str) : v).toList();
        }
        return new ConditionNode(null, n.not(), null, field, n.operator(), value);
    }

    @Override
    public List<Problem> validate(FlowNode node, Flow flow, ValidationContext ctx) {
        ConditionSettings s = settings(node, ConditionSettings.class);
        Problems p = new Problems(node);
        if (s.cases() == null || s.cases().isEmpty()) {
            p.error("cases", "add at least one case");
            return p.list;
        }
        for (int i = 0; i < s.cases().size(); i++) {
            ConditionSettings.Case c = s.cases().get(i);
            String at = "cases[" + i + "]";
            if (c == null || blank(c.id())) {
                p.error(at + ".id", "is required");
                continue;
            }
            p.text(at + ".label", c.label(), 40, false);
            if (c.conditions() == null) {
                p.error(at + ".conditions", "add at least one condition");
                continue;
            }
            walk(c.conditions(), at + ".conditions", p);
        }
        return p.list;
    }

    private static void walk(ConditionNode n, String path, Problems p) {
        if (n.isGroup()) {
            if (!"and".equals(n.combinator()) && !"or".equals(n.combinator())) p.error(path + ".combinator", "must be and or or");
            if (n.rules().isEmpty()) p.error(path + ".rules", "add at least one condition");
            for (int i = 0; i < n.rules().size(); i++) walk(n.rules().get(i), path + ".rules[" + i + "]", p);
            return;
        }
        if (blank(n.field())) p.error(path + ".field", "is required");
        Operator op = n.operator() == null ? null : Operator.of(n.operator()).orElse(null);
        if (op == null) {
            p.error(path + ".operator", "is not a known comparison");
            return;
        }
        if (op == Operator.IS_EMPTY || op == Operator.IS_NOT_EMPTY) return;
        Object v = n.value();
        if (v == null || (v instanceof String str && str.isBlank())) {
            p.error(path + ".value", "is required");
        } else if (op == Operator.BETWEEN && !(v instanceof List<?> l && l.size() == 2 && isNumber(l.get(0)) && isNumber(l.get(1)))) {
            p.error(path + ".value", "must be two numbers, low then high");
        } else if ((op == Operator.GREATER_THAN || op == Operator.LESS_THAN || op == Operator.AT_LEAST || op == Operator.AT_MOST)
                && !isNumber(v) && !(v instanceof String str && str.contains("{{"))) {
            p.error(path + ".value", "must be a number");
        }
    }

    private static boolean isNumber(Object o) {
        if (o instanceof Number) return true;
        if (o instanceof String s) {
            try {
                new BigDecimal(s.trim());
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }
}
