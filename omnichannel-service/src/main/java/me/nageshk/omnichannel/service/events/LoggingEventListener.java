package me.nageshk.omnichannel.service.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import me.nageshk.omnichannel.service.channel.whatsapp.WaIds;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Logs every WhatsApp event: a one-line summary at INFO without message content, and the bodies and payloads at
 * DEBUG only, so customers' conversations stay out of the production logs.
 */
@Component
public class LoggingEventListener {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventListener.class);

    /** WhatsApp ids arrive either flattened to a string or as a Wid object {server,user,_serialized}. */
    public static String wid(JsonNode node) {
        return WaIds.wid(node);
    }

    /** Message key: `_serialized` when present, then `$1`, otherwise rebuilt from its parts. */
    static String messageId(JsonNode id) {
        return WaIds.messageId(id);
    }

    /** " seq=N capturedAt=ISO" for durable events, empty otherwise. */
    static String origin(WhatsAppEvent event) {
        if (event.seq() == null) return "";
        String at = event.ts() == null ? "?" : java.time.Instant.ofEpochMilli(event.ts()).toString();
        return " seq=" + event.seq() + " capturedAt=" + at;
    }

    @EventListener
    public void on(WhatsAppEvent event) {
        JsonNode p = event.payload();
        switch (event.name()) {
            case "message", "message_create" -> {
                log.info("[{}] {} id={} chat={} type={}{}",
                        event.phoneNumberId(), event.name(),
                        messageId(p.path("id")),
                        wid(p.path("id").path("remote")),
                        p.path("type").asString(),
                        origin(event));
                if (log.isDebugEnabled()) {
                    log.debug("[{}] {} id={} from={} to={} body={}", event.phoneNumberId(), event.name(),
                            messageId(p.path("id")), wid(p.path("from")), wid(p.path("to")),
                            p.path("body").asString());
                }
            }
            case "message_ack" -> log.info(
                    "[{}] message_ack id={} ack={}{}",
                    event.phoneNumberId(),
                    messageId(p.path("message").path("id")),
                    p.path("ack").asInt(),
                    origin(event));
            case "ready" -> log.info(
                    "[{}] ready wid={} pushname={} waVersion={}",
                    event.phoneNumberId(),
                    wid(p.path("wid")),
                    p.path("pushname").asString(),
                    p.path("waVersion").asString());
            default -> {
                log.info("[{}] {}{}", event.phoneNumberId(), event.name(), origin(event));
                log.debug("[{}] {} payload={}", event.phoneNumberId(), event.name(), p);
            }
        }
    }
}
