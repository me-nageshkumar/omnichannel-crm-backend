package me.nageshk.omnichannel.service.chatbot.run;

import me.nageshk.omnichannel.db.chatbot.Chatbot;
import me.nageshk.omnichannel.db.chatbot.ChatbotRepository;
import me.nageshk.omnichannel.db.chatbot.ChatbotRun;
import me.nageshk.omnichannel.db.inbox.MessageDirection;
import me.nageshk.omnichannel.db.inbox.MessageSource;
import me.nageshk.omnichannel.service.inbox.InboxEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * An agent's console reply hands the chat over (spec §5.4): the run ends as HANDED_OVER when the chatbot's setting is
 * on. Only CONSOLE sends count: the device echoes the chatbot's own messages as WHATSAPP_WEB before the send result
 * comes back, so a phone-typed reply cannot be told apart from them.
 */
@Component
public class ChatbotHandoverListener {

    private static final Logger log = LoggerFactory.getLogger(ChatbotHandoverListener.class);

    private final ChatbotRunService runs;
    private final ChatbotRepository chatbots;

    public ChatbotHandoverListener(ChatbotRunService runs, ChatbotRepository chatbots) {
        this.runs = runs;
        this.chatbots = chatbots;
    }

    @EventListener
    public void on(InboxEvents.MessageUpserted e) {
        try {
            if (!e.inserted() || e.message().getDirection() != MessageDirection.OUT
                    || e.message().getSource() != MessageSource.CONSOLE) return;
            if (e.chat() == null || e.chat().getChatbot() == null) return;
            ChatbotRun run = runs.activeForChat(e.chat().getId()).orElse(null);
            if (run == null) return;
            Chatbot chatbot = chatbots.findById(run.getChatbotId()).orElse(null);
            if (chatbot != null && chatbot.getSettings() != null && !chatbot.getSettings().stopOnAgentReply()) return;
            runs.handOver(run, e.message().getSentByUserId());
        } catch (RuntimeException ex) {
            log.warn("chatbot hand-over listener failed", ex);
        }
    }
}
