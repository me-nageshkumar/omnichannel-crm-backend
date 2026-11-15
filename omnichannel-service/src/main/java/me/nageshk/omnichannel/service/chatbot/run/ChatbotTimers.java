package me.nageshk.omnichannel.service.chatbot.run;

import java.time.Duration;
import java.time.Instant;
import me.nageshk.omnichannel.db.chatbot.ChatbotRun;
import me.nageshk.omnichannel.db.chatbot.ChatbotSettings;
import me.nageshk.omnichannel.db.chatbot.TaskKind;
import me.nageshk.omnichannel.db.chatbot.Waiting;

/**
 * The inactivity timers of a waiting run (spec §5.6): set on a wait, pushed on an answer, none for a TIMER wait nor
 * while the bot itself is busy on an AI turn (AI node spec §5.5, ruling R2).
 */
public final class ChatbotTimers {

    private ChatbotTimers() {}

    public static void onWait(ChatbotRun run, ChatbotSettings settings, Instant now) {
        Waiting w = run.getWaiting();
        if (w != null && botBusy(w.kind())) {
            run.setRemindAt(null);
            run.setEndAt(null);
            return;
        }
        if (settings.reminderOn() && !run.isReminderSent()) {
            run.setRemindAt(now.plus(Duration.ofMinutes(settings.reminderAfterMinutes())));
        } else {
            run.setRemindAt(null);
        }
        run.setEndAt(now.plus(Duration.ofMinutes(settings.endAfterMinutes())));
    }

    /** A wait on the bot itself, not on the customer: no reminder, no inactivity end, and not a customer answer. */
    public static boolean botBusy(TaskKind kind) {
        return kind == TaskKind.TIMER || kind == TaskKind.AI_RESPONSE;
    }
}
