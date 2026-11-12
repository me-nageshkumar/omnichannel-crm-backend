package me.nageshk.omnichannel.db.chatbot;

/** Per-chatbot behaviour (spec §4.1); {@link #defaults()} is what a new chatbot gets. */
public record ChatbotSettings(Integer reminderAfterMinutes, String reminderMessage, int endAfterMinutes,
        String endMessage, int invalidReplyRetries, String retryMessage, String giveUpMessage,
        boolean stopOnAgentReply) {

    public static final String DEFAULT_RETRY_MESSAGE = "Sorry, I didn't get that. Please choose one of the options.";

    public static ChatbotSettings defaults() {
        return new ChatbotSettings(null, null, 120, null, 2, DEFAULT_RETRY_MESSAGE, null, true);
    }
}
