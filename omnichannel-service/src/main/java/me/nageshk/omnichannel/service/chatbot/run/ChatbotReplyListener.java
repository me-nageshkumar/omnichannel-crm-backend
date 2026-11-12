package me.nageshk.omnichannel.service.chatbot.run;

import java.util.Set;
import me.nageshk.omnichannel.db.chatbot.TaskKind;
import me.nageshk.omnichannel.db.inbox.MessageDirection;
import me.nageshk.omnichannel.db.inbox.MessageSource;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.service.automation.MessageKinds;
import me.nageshk.omnichannel.service.chatbot.node.TaskAnswer;
import me.nageshk.omnichannel.service.inbox.InboxEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The answer source for WhatsApp replies (spec §5.3): a real message from the contact on a chat with an active run
 * is handed to the run waiting on that chat. Reactions, edits, calls, poll votes and notices are ignored.
 */
@Component
public class ChatbotReplyListener implements TaskAnswerSource {

    private static final Logger log = LoggerFactory.getLogger(ChatbotReplyListener.class);
    static final Set<String> EXTRA_TYPES = Set.of("button", "interactive");

    private final ChatbotRunService runs;

    public ChatbotReplyListener(ChatbotRunService runs) {
        this.runs = runs;
    }

    @Override
    public TaskKind kind() {
        return TaskKind.WHATSAPP_REPLY;
    }

    @EventListener
    public void on(InboxEvents.MessageUpserted e) {
        try {
            WaWebMessage m = e.message();
            if (!e.inserted() || m.getDirection() != MessageDirection.IN || m.getSource() != MessageSource.CONTACT) return;
            if (e.chat() == null || e.chat().getChatbot() == null) return;
            String type = m.getType() == null ? "" : m.getType();
            if (!MessageKinds.REAL_TYPES.contains(type) && !EXTRA_TYPES.contains(type)) return;
            runs.answer(TaskAnswer.whatsApp(e.chat().getId(), m));
        } catch (RuntimeException ex) {
            log.warn("chatbot reply listener failed for message {}", e.message() == null ? null : e.message().getId(), ex);
        }
    }
}
