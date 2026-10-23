package me.nageshk.omnichannel.inbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.nageshk.omnichannel.inbox.InboxEvents.ActionRecorded;
import me.nageshk.omnichannel.inbox.InboxEvents.ChatChanged;
import me.nageshk.omnichannel.inbox.InboxEvents.MessageUpserted;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Turns inbox events into live stream pushes. A failed push is logged and swallowed so it never breaks the
 * ingestion or assignment that raised the event.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InboxPushListener {

    private final InboxStreamBroker broker;
    private final InboxViews views;

    @EventListener
    public void on(MessageUpserted event) {
        try {
            WaWebChat chat = event.chat();
            if (broker.subscribers(chat.getWorkspaceId()) == 0) {
                return;
            }
            // no chat push here: every publisher follows MessageUpserted with ChatChanged, or (the media cache) leaves
            // the chat summary as it was
            broker.pushMessage(chat.getWorkspaceId(), chat, views.message(event.message()));
        } catch (RuntimeException e) {
            log.warn("Could not push message {} to the inbox stream", event.message().getId(), e);
        }
    }

    @EventListener
    public void on(ChatChanged event) {
        try {
            WaWebChat chat = event.chat();
            if (broker.subscribers(chat.getWorkspaceId()) == 0) {
                return;
            }
            broker.push(chat.getWorkspaceId(), chat, views.chat(chat));
        } catch (RuntimeException e) {
            log.warn("Could not push chat {} to the inbox stream", event.chat().getId(), e);
        }
    }

    @EventListener
    public void on(ActionRecorded event) {
        try {
            WaWebChat chat = event.chat();
            if (broker.subscribers(chat.getWorkspaceId()) == 0) {
                return;
            }
            broker.pushAction(chat.getWorkspaceId(), chat, views.action(event.action()));
        } catch (RuntimeException e) {
            log.warn("Could not push action {} to the inbox stream", event.action().getId(), e);
        }
    }
}
