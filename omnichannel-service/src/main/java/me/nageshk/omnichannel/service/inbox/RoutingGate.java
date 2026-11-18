package me.nageshk.omnichannel.service.inbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import me.nageshk.omnichannel.db.inbox.ChatStatus;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Holds a new chat back from routing while its message's rules run, so a chatbot gets it before any person
 * (spec 2026-11-18 §5.2). The rule worker, the message listener and the sweep settle it; the first settle wins.
 */
@Service
public class RoutingGate {

    private static final Logger log = LoggerFactory.getLogger(RoutingGate.class);

    private final MongoTemplate mongo;
    private final WaWebChatRepository chats;
    private final ChatRoutingService routing;
    private final BotChats botChats;
    private final ChatbotStarts starts;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;
    private final Duration routingWait;

    public RoutingGate(MongoTemplate mongo, WaWebChatRepository chats, ChatRoutingService routing, BotChats botChats,
            ChatbotStarts starts, ApplicationEventPublisher publisher, Clock clock,
            @Value("${omnichannel.inbox.routing-wait:30s}") Duration routingWait) {
        this.mongo = mongo;
        this.chats = chats;
        this.routing = routing;
        this.botChats = botChats;
        this.starts = starts;
        this.publisher = publisher;
        this.clock = clock;
        this.routingWait = routingWait;
    }

    public Duration routingWait() {
        return routingWait;
    }

    /**
     * A chat routing would act on now. A chatbot that wrote first (into a closed chat) takes it; a brand that may
     * start a chatbot from this message makes it wait for the rules; any other brand routes it at once, as before.
     */
    public void arrive(WaWebChat chat) {
        if (chat.getChatbot() != null && chat.getAssignedUserId() == null) {
            botChats.take(chat.getId());
            return;
        }
        if (starts.mayStartFromMessage(chat.getWorkspaceId())) {
            mongo.updateFirst(new Query(Criteria.where("_id").is(chat.getId())),
                    new Update().set("routingPendingSince", clock.instant()).inc("version", 1), WaWebChat.class);
            return;
        }
        routing.route(chat);
    }

    /**
     * The chat's rules have run (or never will): a waiting chat that is still open with nobody is routed. Only the
     * caller that clears the wait routes it. Never throws.
     *
     * @return true when this call routed the chat
     */
    public boolean settle(String chatId) {
        try {
            WaWebChat chat = mongo.findAndModify(
                    new Query(Criteria.where("_id").is(chatId).and("routingPendingSince").ne(null)),
                    new Update().unset("routingPendingSince").inc("version", 1),
                    FindAndModifyOptions.options().returnNew(true), WaWebChat.class);
            if (chat == null) return false;
            for (int attempt = 0; attempt < 2; attempt++) {
                if (!routable(chat)) return false;
                try {
                    WaWebChat routed = routing.route(chat);
                    publisher.publishEvent(new InboxEvents.ChatChanged(routed));
                    return true;
                } catch (OptimisticLockingFailureException changed) {
                    // a chatbot took it, or a message landed: look again
                    chat = chats.findById(chatId).orElse(null);
                    if (chat == null) return false;
                }
            }
            return false;
        } catch (RuntimeException e) {
            log.warn("could not route chat {} after its rules", chatId, e);
            return false;
        }
    }

    /** Settles every chat that has waited longer than the routing wait; returns how many it routed. */
    public int settleOverdue(Instant now) {
        Query due = new Query(Criteria.where("routingPendingSince").lt(now.minus(routingWait)));
        due.fields().include("_id");
        List<WaWebChat> overdue = mongo.find(due, WaWebChat.class);
        int routed = 0;
        for (WaWebChat c : overdue) if (settle(c.getId())) routed++;
        return routed;
    }

    private static boolean routable(WaWebChat chat) {
        return chat.getStatus() == ChatStatus.OPEN && chat.getAssignedUserId() == null && chat.getAssignedTeamId() == null;
    }
}
