package me.nageshk.omnichannel.service.chatbot.node;

import java.util.Map;
import me.nageshk.omnichannel.db.chatbot.TaskKind;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;

/** The answer to a waiting run's task (spec §5.3): the kind and key it was waiting on, and what arrived. */
public record TaskAnswer(TaskKind kind, String key, Map<String, Object> payload, WaWebMessage message) {

    public static TaskAnswer whatsApp(String chatId, WaWebMessage message) {
        return new TaskAnswer(TaskKind.WHATSAPP_REPLY, chatId, Map.of(), message);
    }

    public static TaskAnswer form(String token, Map<String, Object> answers) {
        return new TaskAnswer(TaskKind.FORM, token, answers, null);
    }

    public static TaskAnswer timer(String key) {
        return new TaskAnswer(TaskKind.TIMER, key, Map.of(), null);
    }
}
