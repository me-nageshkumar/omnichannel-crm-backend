package me.nageshk.omnichannel.service.inbox;

import java.time.Clock;
import java.util.Optional;
import me.nageshk.omnichannel.db.inbox.ActionType;
import me.nageshk.omnichannel.db.inbox.ChatStatus;
import me.nageshk.omnichannel.db.inbox.RoutedReason;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatAction;
import me.nageshk.omnichannel.db.inbox.WaWebChatActionRepository;
import me.nageshk.omnichannel.db.inbox.WaWebChatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Moves a chat into and out of {@link ChatStatus#BOT} (spec 2026-11-18 §5.3). Every move is one guarded update that
 * bumps the chat's version, so it wins or loses whole against a concurrent change and a stale full save fails.
 */
@Service
public class BotChats {

    private static final Logger log = LoggerFactory.getLogger(BotChats.class);

    private final MongoTemplate mongo;
    private final WaWebChatRepository chats;
    private final WaWebChatActionRepository actions;
    private final ChatRoutingService routing;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    public BotChats(MongoTemplate mongo, WaWebChatRepository chats, WaWebChatActionRepository actions,
            ChatRoutingService routing, ApplicationEventPublisher publisher, Clock clock) {
        this.mongo = mongo;
        this.chats = chats;
        this.actions = actions;
        this.routing = routing;
        this.publisher = publisher;
        this.clock = clock;
    }

    /** An open chat nobody holds becomes the chatbot's: out of every person's list and queue. */
    public boolean take(String chatId) {
        Query q = new Query(Criteria.where("_id").is(chatId).and("status").is(ChatStatus.OPEN)
                .and("assignedUserId").is(null));
        Update u = new Update().set("status", ChatStatus.BOT).unset("assignedTeamId").unset("routingPendingSince")
                .unset("handedOverAt").set("assignedAt", clock.instant()).inc("version", 1);
        WaWebChat taken = mongo.findAndModify(q, u, FindAndModifyOptions.options().returnNew(true), WaWebChat.class);
        if (taken == null) return false;
        publisher.publishEvent(new InboxEvents.ChatChanged(taken));
        return true;
    }

    /** A chat the chatbot holds is open again and marked as handed over; the caller goes on to change it. */
    public Optional<WaWebChat> open(String chatId) {
        Query q = new Query(Criteria.where("_id").is(chatId).and("status").is(ChatStatus.BOT));
        Update u = new Update().set("status", ChatStatus.OPEN).set("handedOverAt", clock.instant()).inc("version", 1);
        return Optional.ofNullable(mongo.findAndModify(q, u, FindAndModifyOptions.options().returnNew(true), WaWebChat.class));
    }

    /**
     * The chatbot is done with the chat: it is opened, marked as handed over and routed under the number's team,
     * recorded as asked by the chatbot. Does nothing unless the chat is still the chatbot's and, when {@code runId}
     * is given, held by that run or by none.
     */
    public void toPeople(String chatId, String runId, RoutedReason reason, String chatbotName) {
        Update u = new Update().set("status", ChatStatus.OPEN).set("handedOverAt", clock.instant()).inc("version", 1);
        WaWebChat opened = mongo.findAndModify(heldBy(chatId, runId), u, FindAndModifyOptions.options().returnNew(true),
                WaWebChat.class);
        if (opened == null) return;
        WaWebChat chat = opened;
        try {
            chat = routing.route(opened, new ChatRoutingService.RoutingOptions(null, true, reason,
                    "Chatbot: " + chatbotName, chatbotName));
        } catch (RuntimeException e) {
            log.warn("routing chat {} after its chatbot ended failed; it stays open for the team", chatId, e);
        }
        publisher.publishEvent(new InboxEvents.ChatChanged(chat));
    }

    /** The chatbot is done and the chat is closed, recorded as closed by the chatbot. Same guard as {@link #toPeople}. */
    public void close(String chatId, String runId, String chatbotName) {
        Update u = new Update().set("status", ChatStatus.CLOSED).unset("handedOverAt").inc("version", 1);
        WaWebChat closed = mongo.findAndModify(heldBy(chatId, runId), u, FindAndModifyOptions.options().returnNew(true),
                WaWebChat.class);
        if (closed == null) return;
        WaWebChatAction a = new WaWebChatAction();
        a.setCustomerId(closed.getCustomerId());
        a.setWorkspaceId(closed.getWorkspaceId());
        a.setChatId(closed.getId());
        a.setType(ActionType.CLOSED);
        a.setByName("Chatbot: " + chatbotName);
        a.setAt(clock.instant());
        a = actions.save(a);
        publisher.publishEvent(new InboxEvents.ActionRecorded(closed, a));
        publisher.publishEvent(new InboxEvents.ChatChanged(closed));
    }

    /** People have the chat from a chatbot although it was not the chatbot's: no chatbot starts on it from a rule. */
    public void markHandedOver(String chatId) {
        mongo.updateFirst(new Query(Criteria.where("_id").is(chatId)),
                new Update().set("handedOverAt", clock.instant()).inc("version", 1), WaWebChat.class);
    }

    private static Query heldBy(String chatId, String runId) {
        Criteria c = Criteria.where("_id").is(chatId).and("status").is(ChatStatus.BOT);
        if (runId != null) {
            c = c.orOperator(Criteria.where("chatbot").is(null), Criteria.where("chatbot.runId").is(runId));
        }
        return new Query(c);
    }
}
