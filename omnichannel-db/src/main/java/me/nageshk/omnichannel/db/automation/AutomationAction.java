package me.nageshk.omnichannel.db.automation;

import java.util.Map;
import me.nageshk.omnichannel.db.chatbot.IfRunning;

/** One step of a rule; the fields used depend on {@code type}. */
public record AutomationAction(AutomationActionType type, String templateId, Map<String, String> values,
        AssignMode mode, String userId, String teamId, WhenAway whenAway, String pipelineId, String stageId,
        String dealTitle, String chatbotId, IfRunning ifRunning) {
}
