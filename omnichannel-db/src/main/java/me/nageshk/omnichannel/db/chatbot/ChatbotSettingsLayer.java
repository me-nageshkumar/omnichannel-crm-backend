package me.nageshk.omnichannel.db.chatbot;

/**
 * One layer of chatbot settings: the platform defaults, a brand's, or a chatbot's own. A {@code null} field is inherited
 * from the layer above; {@code ""} in {@link #endMessage} or {@link #giveUpMessage} means "send no message".
 */
public record ChatbotSettingsLayer(Boolean reminderOn, Integer reminderAfterMinutes, String reminderMessage,
        Integer endAfterMinutes, String endMessage, Integer invalidReplyRetries, String retryMessage,
        String giveUpMessage, Boolean stopOnAgentReply) {

    public static ChatbotSettingsLayer empty() {
        return new ChatbotSettingsLayer(null, null, null, null, null, null, null, null, null);
    }
}
