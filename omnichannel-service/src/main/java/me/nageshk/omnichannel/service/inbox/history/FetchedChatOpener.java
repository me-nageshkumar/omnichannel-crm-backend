package me.nageshk.omnichannel.service.inbox.history;

import java.time.Clock;
import java.time.Instant;
import me.nageshk.omnichannel.common.config.OmnichannelProperties;
import me.nageshk.omnichannel.db.inbox.ChatStatus;
import me.nageshk.omnichannel.db.inbox.MessageDirection;
import me.nageshk.omnichannel.db.inbox.RoutedReason;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatRepository;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImport;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImportChat;
import me.nageshk.omnichannel.service.inbox.ChatRoutingService;
import me.nageshk.omnichannel.service.inbox.InboxEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * When a fetch of a chat ends, opens the chat and routes it like a new one if it waits on us: the fetch created it,
 * the page says it is not with a business, the fetch reaches the time it was asked for, the contact wrote within
 * {@code waitingWindow}, and it is unread on the phone or the contact wrote last. Anything else stays as the fetch left it: a created chat Closed and unassigned, an existing one
 * in whatever state the inbox gave it.
 */
@Component
class FetchedChatOpener {

    private static final Logger log = LoggerFactory.getLogger(FetchedChatOpener.class);

    private final MongoTemplate mongo;
    private final WaWebChatRepository chats;
    private final ChatRoutingService routing;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final OmnichannelProperties.History settings;

    FetchedChatOpener(MongoTemplate mongo, WaWebChatRepository chats, ChatRoutingService routing,
            ApplicationEventPublisher events, Clock clock, OmnichannelProperties props) {
        this.mongo = mongo;
        this.chats = chats;
        this.routing = routing;
        this.events = events;
        this.clock = clock;
        this.settings = props.history();
    }

    /** {@code item} as it stands when its chat's fetch, part of {@code imp}, ended DONE. */
    void openIfWaiting(WaWebHistoryImport imp, WaWebHistoryImportChat item) {
        // a page script that does not say it is a person may be a business: it stays closed
        if (item.getCreatedChatId() == null || !Boolean.FALSE.equals(item.getBusiness())) return;
        // a fetch that ends before it was asked for cannot see what came after, our replies included
        if (endsBeforeNow(imp)) return;
        WaWebChat chat = chats.findById(item.getCreatedChatId()).orElse(null);
        if (chat == null || !waits(chat, item.getPhoneUnread())) return;
        int unread = item.getPhoneUnread() == null ? 0 : Math.max(0, item.getPhoneUnread());
        // only as the fetch left it: a live message may have opened or routed it meanwhile
        boolean opened = mongo.updateFirst(new Query(Criteria.where("_id").is(chat.getId())
                .and("status").is(ChatStatus.CLOSED).and("assignedUserId").is(null).and("assignedTeamId").is(null)),
                new Update().set("status", ChatStatus.OPEN).set("unreadCount", unread), WaWebChat.class)
                .getModifiedCount() > 0;
        if (!opened) return;
        route(chat.getId());
        chats.findById(chat.getId()).ifPresent(stored -> events.publishEvent(new InboxEvents.ChatChanged(stored)));
    }

    private static boolean endsBeforeNow(WaWebHistoryImport imp) {
        Instant to = imp.getRange() == null ? null : imp.getRange().to();
        return to != null && imp.getCreationTime() != null && to.isBefore(imp.getCreationTime());
    }

    /**
     * Routes the open chat as stored. A live message saved in between makes the routing's save fail on the version;
     * the live path routes no open chat, so this reads it again and tries once more.
     */
    private void route(String chatId) {
        for (int attempt = 0; attempt < 2; attempt++) {
            WaWebChat open = chats.findById(chatId).orElse(null);
            if (open == null || open.getStatus() != ChatStatus.OPEN || open.getAssignedUserId() != null
                    || open.getAssignedTeamId() != null) {
                return;
            }
            try {
                routing.route(open, RoutedReason.FETCHED);
                return;
            } catch (OptimisticLockingFailureException e) {
                log.info("fetched chat {} changed while it was routed, routing it again", chatId);
            } catch (RuntimeException e) {
                // like a live chat: a routing failure leaves it open and unassigned
                log.warn("routing fetched chat {} failed", chatId, e);
                return;
            }
        }
    }

    private boolean waits(WaWebChat chat, Integer phoneUnread) {
        if (chat.getStatus() != ChatStatus.CLOSED || chat.getAssignedUserId() != null
                || chat.getAssignedTeamId() != null) {
            return false;
        }
        Instant since = clock.instant().minus(settings.waitingWindow());
        if (chat.getLastInboundAt() == null || chat.getLastInboundAt().isBefore(since)) return false;
        boolean contactWroteLast = chat.getLastMessage() != null
                && chat.getLastMessage().direction() == MessageDirection.IN;
        return contactWroteLast || phoneUnread != null && phoneUnread != 0;
    }
}
