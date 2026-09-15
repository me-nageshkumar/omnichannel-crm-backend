package me.nageshk.whatsapp.events;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** PoC consumer: logs every WhatsApp event. Later consumers (DB, webhooks) subscribe the same way. */
@Component
public class LoggingEventListener {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventListener.class);

    /** WhatsApp ids arrive either flattened to a string or as a Wid object {server,user,_serialized}. */
    static String wid(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";
        if (node.isTextual()) return node.asText();
        if (node.isObject()) {
            JsonNode s = node.get("_serialized");
            if (s != null && s.isTextual()) return s.asText();
            JsonNode user = node.get("user");
            JsonNode server = node.get("server");
            if (user != null && server != null) return user.asText() + "@" + server.asText();
        }
        return node.toString();
    }

    /** Message key: `_serialized` when present, otherwise rebuilt from its parts. */
    static String messageId(JsonNode id) {
        if (id == null || !id.isObject()) return "";
        String s = id.path("_serialized").asText("");
        if (!s.isEmpty()) return s;
        String remote = wid(id.get("remote"));
        if (remote.isEmpty() || !id.hasNonNull("id")) return "";
        return id.path("fromMe").asBoolean(false) + "_" + remote + "_" + id.path("id").asText();
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
            case "message", "message_create" -> log.info(
                    "[{}] {} id={} chat={} from={} to={} type={} body={}{}",
                    event.username(), event.name(),
                    messageId(p.path("id")),
                    wid(p.path("id").path("remote")),
                    wid(p.path("from")),
                    wid(p.path("to")),
                    p.path("type").asText(),
                    p.path("body").asText(),
                    origin(event));
            case "message_ack" -> log.info(
                    "[{}] message_ack id={} ack={}{}",
                    event.username(),
                    messageId(p.path("message").path("id")),
                    p.path("ack").asInt(),
                    origin(event));
            case "ready" -> log.info(
                    "[{}] ready wid={} pushname={} waVersion={}",
                    event.username(),
                    wid(p.path("wid")),
                    p.path("pushname").asText(),
                    p.path("waVersion").asText());
            default -> log.info("[{}] {} {}", event.username(), event.name(), p);
        }
    }
}
