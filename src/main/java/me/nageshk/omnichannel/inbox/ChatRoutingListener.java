package me.nageshk.omnichannel.inbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Routes chats as {@link InboxIngestService} reports them, on the ingesting thread. A routing failure is logged
 * and the chat stays unassigned: it must not stop the message from reaching the streams.
 */
@Component
public class ChatRoutingListener {

    private static final Logger log = LoggerFactory.getLogger(ChatRoutingListener.class);

    private final ChatRoutingService routing;

    public ChatRoutingListener(ChatRoutingService routing) {
        this.routing = routing;
    }

    /** Only a chat the contact wrote first is routed; whoever wrote first from our side owns it (a campaign waits). */
    @EventListener
    void on(InboxEvents.ChatCreated e) {
        if (e.firstDirection() == MessageDirection.IN) route(e.chat());
    }

    @EventListener
    void on(InboxEvents.ChatReopened e) {
        routeUnassigned(e.chat());
    }

    /** A campaign chat waits unassigned for the contact's first reply. */
    @EventListener
    void on(InboxEvents.CampaignChatFirstReply e) {
        routeUnassigned(e.chat());
    }

    private void routeUnassigned(WaWebChat chat) {
        if (chat.getAssignedUserId() == null && chat.getAssignedTeamId() == null) route(chat);
    }

    private void route(WaWebChat chat) {
        try {
            routing.route(chat);
        } catch (RuntimeException ex) {
            log.warn("routing chat {} failed", chat.getId(), ex);
        }
    }
}
