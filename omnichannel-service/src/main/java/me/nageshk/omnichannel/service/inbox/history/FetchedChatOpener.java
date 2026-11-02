package me.nageshk.omnichannel.service.inbox.history;

import java.time.Clock;
import java.time.Instant;
import me.nageshk.omnichannel.common.config.OmnichannelProperties;
import me.nageshk.omnichannel.db.inbox.ChatStatus;
import me.nageshk.omnichannel.db.inbox.MessageDirection;
import me.nageshk.omnichannel.db.inbox.RoutedReason;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatRepository;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImportChat;
import me.nageshk.omnichannel.service.inbox.ChatRoutingService;
import me.nageshk.omnichannel.service.inbox.InboxEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * When a fetch of a chat ends, opens the chat and routes it like a new one if it waits on us: the fetch created it,
 * it is not with a business, the contact wrote within {@code waitingWindow}, and it is unread on the phone or the
 * contact wrote last. Anything else stays as the fetch left it: a created chat Closed and unassigned, an existing one
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

    /** {@code item} as it stands when its chat's fetch ended DONE. */
    void openIfWaiting(WaWebHistoryImportChat item) {
        if (!item.isChatCreated() || Boolean.TRUE.equals(item.getBusiness())) return;
        WaWebChat chat = chats.findByPhoneNumberIdAndRemoteId(item.getPhoneNumberId(), item.getRemoteId())
                .orElse(null);
        if (chat == null || !waits(chat, item.getPhoneUnread())) return;
        int unread = item.getPhoneUnread() == null ? 0 : Math.max(0, item.getPhoneUnread());
        // only as the fetch left it: a live message may have opened or routed it meanwhile
        boolean opened = mongo.updateFirst(new Query(Criteria.where("_id").is(chat.getId())
                .and("status").is(ChatStatus.CLOSED).and("assignedUserId").is(null).and("assignedTeamId").is(null)),
                new Update().set("status", ChatStatus.OPEN).set("unreadCount", unread), WaWebChat.class)
                .getModifiedCount() > 0;
        if (!opened) return;
        WaWebChat open = chats.findById(chat.getId()).orElse(chat);
        try {
            open = routing.route(open, RoutedReason.FETCHED);
        } catch (RuntimeException e) {
            // like a live chat: a routing failure leaves it open and unassigned
            log.warn("routing fetched chat {} failed", open.getId(), e);
        }
        events.publishEvent(new InboxEvents.ChatChanged(open));
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
