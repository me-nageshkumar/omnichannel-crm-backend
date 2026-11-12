package me.nageshk.omnichannel.db.chatbot;

/** Who started a run: an automation rule on an event, or a person testing from the editor. */
public record StartedBy(String ruleId, String ruleName, String eventId, String userId) {

    public static StartedBy test(String userId) {
        return new StartedBy(null, "Test", null, userId);
    }
}
