package me.nageshk.omnichannel.db.chatbot;

/**
 * The settings a run uses, resolved from the chatbot, brand and platform layers. {@link #endMessage} and
 * {@link #giveUpMessage} are {@code null} when no message is sent.
 */
public record ChatbotSettings(boolean reminderOn, int reminderAfterMinutes, String reminderMessage,
        int endAfterMinutes, String endMessage, int invalidReplyRetries, String retryMessage, String giveUpMessage,
        boolean stopOnAgentReply) {

    public static final String DEFAULT_RETRY_MESSAGE = "Sorry, I didn't get that. Please choose one of the options.";
    public static final String DEFAULT_REMINDER_MESSAGE = "Are you still there? Reply to carry on.";

    /** The platform defaults until the super admin saves their own. */
    public static ChatbotSettings codeDefaults() {
        return new ChatbotSettings(false, 30, DEFAULT_REMINDER_MESSAGE, 120, null, 2, DEFAULT_RETRY_MESSAGE, null, true);
    }

    /** As a complete layer: every field set, no message stored as {@code ""}. */
    public ChatbotSettingsLayer asLayer() {
        return new ChatbotSettingsLayer(reminderOn, reminderAfterMinutes, reminderMessage, endAfterMinutes,
                endMessage == null ? "" : endMessage, invalidReplyRetries, retryMessage,
                giveUpMessage == null ? "" : giveUpMessage, stopOnAgentReply);
    }
}
