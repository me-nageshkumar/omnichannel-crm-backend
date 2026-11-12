package me.nageshk.omnichannel.service.chatbot.node;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import me.nageshk.omnichannel.db.automation.ConditionNode;
import me.nageshk.omnichannel.service.automation.Operator;
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
