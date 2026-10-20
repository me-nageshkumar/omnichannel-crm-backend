package me.nageshk.omnichannel.inbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import me.nageshk.omnichannel.events.WhatsAppEvent;

/**
 * Turns the WhatsApp Web events of a number into chats and messages; never throws.
 * {@code message_create} fires for both directions, so {@code message} (inbound only) is ignored.
 */
@Component
public class WaWebInboxListener {

    private static final Logger log = LoggerFactory.getLogger(WaWebInboxListener.class);

    private final InboxIngestService ingest;

    public WaWebInboxListener(InboxIngestService ingest) {
        this.ingest = ingest;
    }

    @EventListener
    public void on(WhatsAppEvent event) {
        try {
            switch (event.name()) {
                case "message_create" -> ingest.ingestMessage(event);
                case "message_ack" -> ingest.ingestAck(event);
                default -> { }
            }
        } catch (RuntimeException e) {
            log.warn("could not ingest {} event of {}: {}", event.name(), event.phoneNumberId(), e.toString());
        }
    }
}
