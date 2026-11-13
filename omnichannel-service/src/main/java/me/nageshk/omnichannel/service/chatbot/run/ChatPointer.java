package me.nageshk.omnichannel.service.chatbot.run;

import me.nageshk.omnichannel.db.chatbot.ChatbotRun;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatRepository;
import me.nageshk.omnichannel.service.inbox.InboxEvents;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * The chat's pointer to its active run (spec §5.1): set, moved along, cleared; each change pushes the chat row. Every
 * change bumps the chat's version, so a full save of a copy loaded before it fails instead of wiping the pointer.
 */
@Component
public class ChatPointer {

    private final MongoTemplate mongo;
    private final WaWebChatRepository chats;
    private final ApplicationEventPublisher publisher;

    public ChatPointer(MongoTemplate mongo, WaWebChatRepository chats, ApplicationEventPublisher publisher) {
        this.mongo = mongo;
        this.chats = chats;
        this.publisher = publisher;
    }

    public void set(String chatId, ChatbotRun run) {
        if (chatId == null) return;
        WaWebChat.ActiveChatbot p = new WaWebChat.ActiveChatbot(run.getId(), run.getChatbotId(), run.getChatbotName(),
                run.getCurrentNodeName());
        mongo.updateFirst(new Query(Criteria.where("_id").is(chatId)), new Update().set("chatbot", p).inc("version", 1), WaWebChat.class);
        changed(chatId);
    }

    /** Clears the pointer only when it still points at this run. */
    public void clear(String chatId, String runId) {
        if (chatId == null) return;
        mongo.updateFirst(new Query(Criteria.where("_id").is(chatId).and("chatbot.runId").is(runId)),
                new Update().unset("chatbot").inc("version", 1), WaWebChat.class);
        changed(chatId);
    }

    private void changed(String chatId) {
        chats.findById(chatId).ifPresent(c -> publisher.publishEvent(new InboxEvents.ChatChanged(c)));
    }
}
