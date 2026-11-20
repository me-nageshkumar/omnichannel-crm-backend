package me.nageshk.omnichannel.db.automation;

import java.util.Map;
import me.nageshk.omnichannel.db.chatbot.IfRunning;

/** One step of a rule; the fields used depend on {@code type}. */
public record AutomationAction(AutomationActionType type, String templateId, Map<String, String> values,
        AssignMode mode, String userId, String teamId, WhenAway whenAway, String pipelineId, String stageId,
        String dealTitle, String chatbotId, IfRunning ifRunning, String cloudTemplateId) {

    /** Any action but a WhatsApp template's (the shape before Cloud templates were added). */
    public AutomationAction(AutomationActionType type, String templateId, Map<String, String> values,
            AssignMode mode, String userId, String teamId, WhenAway whenAway, String pipelineId, String stageId,
            String dealTitle, String chatbotId, IfRunning ifRunning) {
        this(type, templateId, values, mode, userId, teamId, whenAway, pipelineId, stageId, dealTitle, chatbotId,
                ifRunning, null);
    }
}
