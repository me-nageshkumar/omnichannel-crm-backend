package me.nageshk.omnichannel.service.chatbot.run;

import java.time.Duration;
import java.time.Instant;
import me.nageshk.omnichannel.db.chatbot.ChatbotRun;
import me.nageshk.omnichannel.db.chatbot.ChatbotSettings;
import me.nageshk.omnichannel.db.chatbot.TaskKind;
import me.nageshk.omnichannel.db.chatbot.Waiting;

/** The inactivity timers of a waiting run (spec §5.6): set on a wait, pushed on an answer, none for a TIMER wait. */
public final class ChatbotTimers {

    private ChatbotTimers() {}

    public static void onWait(ChatbotRun run, ChatbotSettings settings, Instant now) {
        Waiting w = run.getWaiting();
        if (w != null && w.kind() == TaskKind.TIMER) {
            run.setRemindAt(null);
            run.setEndAt(null);
            return;
        }
        if (settings.reminderAfterMinutes() != null && !run.isReminderSent()) {
            run.setRemindAt(now.plus(Duration.ofMinutes(settings.reminderAfterMinutes())));
        } else {
            run.setRemindAt(null);
        }
        run.setEndAt(now.plus(Duration.ofMinutes(settings.endAfterMinutes())));
    }
}
