package me.nageshk.omnichannel.db.chatbot;

import java.time.Instant;

/** The task a run waits for: its kind, the key its answer will carry, when the wait began, and a prompt to show. */
public record Waiting(TaskKind kind, String key, Instant since, Instant expiresAt, String prompt) {

    public static Waiting whatsApp(String chatId, Instant since, String prompt) {
        return new Waiting(TaskKind.WHATSAPP_REPLY, chatId, since, null, prompt);
    }

    public static Waiting form(String token, Instant since, Instant expiresAt, String prompt) {
        return new Waiting(TaskKind.FORM, token, since, expiresAt, prompt);
    }

    /** A timer's key is its resume time as 13 zero-padded epoch millis, so string order is time order. */
    public static Waiting timer(Instant resumeAt, Instant since) {
        return new Waiting(TaskKind.TIMER, timerKey(resumeAt), since, null, null);
    }

    public static String timerKey(Instant at) {
        return String.format("%013d", at.toEpochMilli());
    }
}
