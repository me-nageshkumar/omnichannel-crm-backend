package me.nageshk.omnichannel.service.inbox;

import java.time.Instant;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.service.events.LoggingEventListener;
import me.nageshk.omnichannel.service.events.WhatsAppEvent;
import tools.jackson.databind.JsonNode;

/** Reads the fields of a whatsapp-web.js message payload; every helper tolerates missing nodes. */
final class MessagePayloads {

    private MessagePayloads() {
    }

    /** The chat id the message belongs to, e.g. {@code 919812345678@c.us}. */
    static String remote(JsonNode p) {
        return LoggingEventListener.wid(p.path("id").path("remote"));
    }

    /** {@code id._serialized}, else rebuilt as {@code fromMe_remote_id}; empty when neither is available. */
    static String waMessageId(JsonNode p) {
        JsonNode id = p.path("id");
        String serialized = text(id.path("_serialized"));
        if (serialized != null) return serialized;
        String remote = remote(p);
        String key = text(id.path("id"));
        if (remote.isEmpty() || key == null) return "";
        return fromMe(p) + "_" + remote + "_" + key;
    }

    static boolean fromMe(JsonNode p) {
        return p.path("fromMe").asBoolean(p.path("id").path("fromMe").asBoolean(false));
    }

    static String type(JsonNode p) {
        String t = text(p.path("type"));
        return t == null ? "chat" : t;
    }

    static String body(JsonNode p) {
        return p.path("body").isTextual() ? p.path("body").asString() : null;
    }

    /** WhatsApp's time in seconds, else the capture time of the event, else now. */
    static Instant timestamp(JsonNode p, WhatsAppEvent event) {
        return timestamp(p, event.ts() == null ? Instant.now() : Instant.ofEpochMilli(event.ts()));
    }

    /** WhatsApp's time in seconds, else {@code fallback}. */
    static Instant timestamp(JsonNode p, Instant fallback) {
        long seconds = p.path("timestamp").asLong(0);
        return seconds > 0 ? Instant.ofEpochSecond(seconds) : fallback;
    }

    static int ack(JsonNode p) {
        return p.path("ack").asInt(0);
    }

    static boolean hasMedia(JsonNode p) {
        return p.path("hasMedia").asBoolean(false);
    }

    /** {@code mimetype/filename/size} from the top level, falling back to {@code _data}; null without media. */
    static WaWebMessage.Media media(JsonNode p) {
        if (!hasMedia(p)) return null;
        JsonNode data = p.path("_data");
        String mimetype = firstText(p.path("mimetype"), data.path("mimetype"));
        String filename = firstText(p.path("filename"), data.path("filename"));
        Long size = null;
        for (JsonNode n : new JsonNode[] {p.path("size"), data.path("size")}) {
            if (n.isNumber()) {
                size = n.asLong();
                break;
            }
        }
        return new WaWebMessage.Media(mimetype, filename, size, null, null);
    }

    /** {@code location.latitude/longitude/description}, else {@code _data.lat/lng/loc}; null when absent. */
    static WaWebMessage.Location location(JsonNode p) {
        JsonNode loc = p.path("location");
        JsonNode data = p.path("_data");
        Double lat = number(loc.path("latitude"), data.path("lat"));
        Double lng = number(loc.path("longitude"), data.path("lng"));
        if (lat == null || lng == null) return null;
        return new WaWebMessage.Location(lat, lng, firstText(loc.path("description"), data.path("loc")));
    }

    static String senderName(JsonNode p) {
        for (JsonNode n : new JsonNode[] {p.path("notifyName"), p.path("_data").path("notifyName"), p.path("pushname")}) {
            String s = n.isTextual() ? n.asString().trim() : "";
            if (!s.isEmpty()) return s;
        }
        return null;
    }

    /** The phone behind a {@code @lid} chat when WhatsApp exposed it, digits only. */
    static String lidPhone(JsonNode p) {
        for (JsonNode n : new JsonNode[] {p.path("senderPn"), p.path("_data").path("senderPn"), p.path("author")}) {
            String w = LoggingEventListener.wid(n);
            if (w.endsWith("@c.us")) return w.substring(0, w.length() - "@c.us".length());
        }
        return null;
    }

    private static String text(JsonNode n) {
        return n.isTextual() && !n.asString().isEmpty() ? n.asString() : null;
    }

    private static String firstText(JsonNode... nodes) {
        for (JsonNode n : nodes) {
            String s = text(n);
            if (s != null) return s;
        }
        return null;
    }

    private static Double number(JsonNode... nodes) {
        for (JsonNode n : nodes) {
            if (n.isNumber()) return n.asDouble();
        }
        return null;
    }
}
