package me.nageshk.omnichannel.service.contact;

import me.nageshk.omnichannel.db.inbox.MessageDirection;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.service.inbox.InboxEvents;
import me.nageshk.omnichannel.service.inbox.InboxEvents.MessageUpserted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;

/**
 * A newly stored inbound message hands its contact to {@link ContactIdResolver}, which looks up the person's other
 * WhatsApp id when the contact lacks one (spec 4.3). Returns at once: the resolver's own thread does the rest, and a
 * failure is logged, never thrown into ingest. {@code omnichannel.contacts.resolve-on-message=false} (tests) leaves it
 * out, so fake devices see only the requests a test expects.
 */
@Component
@ConditionalOnProperty(name = "omnichannel.contacts.resolve-on-message", havingValue = "true", matchIfMissing = true)
class ContactResolveListener {

    private static final Logger log = LoggerFactory.getLogger(ContactResolveListener.class);

    private final ContactIdResolver resolver;

    ContactResolveListener(ContactIdResolver resolver) {
        this.resolver = resolver;
    }

    @EventListener
    public void on(MessageUpserted event) {
        WaWebChat chat = event.chat();
        if (!event.inserted() || event.message().getDirection() != MessageDirection.IN || chat.getContactId() == null) {
            return;
        }
        queue(chat);
    }

    /**
     * A chat whose history fetch ended: the phone behind a hidden id is never on a stored message (live tab,
     * 2026-11-05), only on the device's contact, and the device is online during a fetch by definition.
     */
    @EventListener
    public void on(InboxEvents.ChatFetched event) {
        if (event.chat().getContactId() != null) queue(event.chat());
    }

    private void queue(WaWebChat chat) {
        try {
            resolver.afterMessage(chat.getWorkspaceId(), chat.getPhoneNumberId(), chat.getContactId());
        } catch (TaskRejectedException e) {
            log.debug("contact {} not queued for resolution: the application is stopping", chat.getContactId());
        } catch (RuntimeException e) {
            log.warn("contact {} not queued for resolution: {}", chat.getContactId(), e.toString());
        }
    }
}
