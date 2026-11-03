package me.nageshk.omnichannel.db.automation;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** A node of a rule's condition tree: a group (combinator and rules) or a leaf (field, operator, value). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConditionNode(String combinator, Boolean not, List<ConditionNode> rules, String field, String operator,
        Object value) {

    public boolean isGroup() {
        return rules != null;
    }
}
