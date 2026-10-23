package me.nageshk.omnichannel.db.contact.advanced;

import java.util.List;

/** A combinator over rules and groups; {@code not} negates the whole group and reads as false when absent. */
public record RuleGroup(String combinator, Boolean not, List<RuleNode> rules) implements RuleNode {

    public RuleGroup {
        not = Boolean.TRUE.equals(not);
    }
}
