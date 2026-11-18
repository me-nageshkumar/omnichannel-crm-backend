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
    private final me.nageshk.omnichannel.service.inbox.RoutingGate routingGate;

    AutomationMessageListener(AutomationEventWriter writer, me.nageshk.omnichannel.service.inbox.RoutingGate routingGate) {
        this.writer = writer;
        this.routingGate = routingGate;
    }

    @EventListener
    void on(InboxEvents.MessageUpserted e) {
        WaWebMessage m = e.message();
        try {
            if (!e.inserted() || m.getDirection() != MessageDirection.IN) return;
            boolean queued = m.getSource() == MessageSource.CONTACT && m.getType() != null
                    && MessageKinds.REAL_TYPES.contains(m.getType()) && writer.messageReceived(e.chat(), m);
            // no rule will run for this message: a chat waiting for its rules goes to people now
            if (!queued && e.chat() != null) routingGate.settle(e.chat().getId());
        } catch (RuntimeException ex) {
            log.warn("could not queue the automation event of message {}: {}", m == null ? null : m.getId(),
                    ex.toString());
        }
    }
}
