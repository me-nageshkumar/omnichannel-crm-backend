package in.redlava.wabridge.events;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** PoC consumer: logs every WhatsApp event. Later consumers (DB, webhooks) subscribe the same way. */
@Component
public class LoggingEventListener {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventListener.class);

    @EventListener
    public void on(WhatsAppEvent event) {
        JsonNode p = event.payload();
        switch (event.name()) {
            case "message", "message_create" -> log.info(
                    "[{}] {} id={} chat={} from={} type={} body={}",
                    event.username(), event.name(),
                    p.path("id").path("_serialized").asText(),
                    p.path("id").path("remote").asText(),
                    p.path("from").asText(),
                    p.path("type").asText(),
                    p.path("body").asText());
            case "message_ack" -> log.info(
                    "[{}] message_ack id={} ack={}",
                    event.username(),
                    p.path("message").path("id").path("_serialized").asText(),
                    p.path("ack").asInt());
            case "ready" -> log.info(
                    "[{}] ready wid={} pushname={} waVersion={}",
                    event.username(),
                    p.path("wid").path("_serialized").asText(),
                    p.path("pushname").asText(),
                    p.path("waVersion").asText());
            default -> log.info("[{}] {} {}", event.username(), event.name(), p);
        }
    }
}
