package me.nageshk.omnichannel.crm.segment;

import java.util.List;

/** A combinator over rules and groups; {@code not} negates the whole group and reads as false when absent. */
public record SegmentRuleGroup(String combinator, Boolean not, List<SegmentRuleNode> rules) implements SegmentRuleNode {

    public SegmentRuleGroup {
        not = Boolean.TRUE.equals(not);
        rules = rules == null ? List.of() : List.copyOf(rules);
    }
}
