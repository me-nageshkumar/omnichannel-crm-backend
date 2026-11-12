package me.nageshk.omnichannel.service.page;

import me.nageshk.omnichannel.db.inbox.MessageDirection;
import me.nageshk.omnichannel.service.inbox.InboxEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Hands each new inbound message to {@link MessagePageService#matchReply}; never fails the ingest that raised it. */
@Component
public class MessagePageReplyListener {

    private static final Logger log = LoggerFactory.getLogger(MessagePageReplyListener.class);

    private final MessagePageService pages;

    public MessagePageReplyListener(MessagePageService pages) {
        this.pages = pages;
    }

    @EventListener
    public void on(InboxEvents.MessageUpserted e) {
        if (e.message().getDirection() != MessageDirection.IN || !e.inserted()) return;
        try {
            pages.matchReply(e.chat(), e.message());
        } catch (RuntimeException ex) {
            log.warn("could not match message {} to a message page", e.message().getId(), ex);
        }
    }
}
