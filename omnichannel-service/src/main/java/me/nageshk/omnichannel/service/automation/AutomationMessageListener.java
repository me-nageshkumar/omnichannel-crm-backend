package me.nageshk.omnichannel.service.automation;

import me.nageshk.omnichannel.db.inbox.MessageDirection;
import me.nageshk.omnichannel.db.inbox.MessageSource;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.service.inbox.InboxEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Hears every stored message after routing; only a new, real message the contact sent starts an event. Nothing here
 * may break the ingestion, so every failure is logged and dropped.
 */
@Component
public class AutomationMessageListener {

    private static final Logger log = LoggerFactory.getLogger(AutomationMessageListener.class);

    private final AutomationEventWriter writer;

    AutomationMessageListener(AutomationEventWriter writer) {
        this.writer = writer;
    }

    @EventListener
    void on(InboxEvents.MessageUpserted e) {
        WaWebMessage m = e.message();
        if (!e.inserted() || m.getDirection() != MessageDirection.IN || m.getSource() != MessageSource.CONTACT
                || !MessageKinds.REAL_TYPES.contains(m.getType())) return;
        try {
            writer.messageReceived(e.chat(), m);
        } catch (RuntimeException ex) {
            log.warn("could not queue the automation event of message {}: {}", m.getId(), ex.toString());
        }
    }
}
