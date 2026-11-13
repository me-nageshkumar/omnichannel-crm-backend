package me.nageshk.omnichannel.service.chatbot.flow.settings;

import java.util.List;
import me.nageshk.omnichannel.db.automation.ConditionNode;

/** Ordered cases; the first whose conditions match wins, else {@code otherwise}. */
public record ConditionSettings(List<Case> cases) {

    public record Case(String id, String label, ConditionNode conditions) {
    }
}
