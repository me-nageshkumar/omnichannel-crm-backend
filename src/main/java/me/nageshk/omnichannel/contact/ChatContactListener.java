package me.nageshk.omnichannel.contact;

import tools.jackson.databind.JsonNode;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import me.nageshk.omnichannel.events.LoggingEventListener;
import me.nageshk.omnichannel.events.WhatsAppEvent;

/** Creates and updates a brand's contacts from the chat messages its numbers see; never throws. */
@Component
public class ChatContactListener {

    private static final Logger log = LoggerFactory.getLogger(ChatContactListener.class);

    private final ContactService contacts;

    public ChatContactListener(ContactService contacts) {
        this.contacts = contacts;
    }

    @EventListener
    public void on(WhatsAppEvent event) {
        try {
            if (!"message".equals(event.name()) && !"message_create".equals(event.name())) return;
            JsonNode p = event.payload();
            if (p == null || !p.isObject()) return;
            String remote = LoggingEventListener.wid(p.path("id").path("remote"));
            boolean lid = remote.endsWith("@lid");
            if (!lid && !remote.endsWith("@c.us")) return;
            boolean fromMe = p.path("fromMe").asBoolean(p.path("id").path("fromMe").asBoolean(false));
            contacts.upsertFromChat(event.workspaceId(), event.customerId(), remote,
                    fromMe ? null : senderName(p), lid ? lidPhone(p) : null, messageTime(p, event));
        } catch (RuntimeException e) {
            log.warn("could not record contact for {} event of {}: {}", event.name(), event.phoneNumberId(),
                    e.toString());
        }
    }

    private static String senderName(JsonNode p) {
        for (JsonNode n : new JsonNode[] {p.path("notifyName"), p.path("_data").path("notifyName"), p.path("pushname")}) {
            String s = n.isTextual() ? n.asString().trim() : "";
            if (!s.isEmpty()) return s;
        }
        return null;
    }

    private static String lidPhone(JsonNode p) {
        for (JsonNode n : new JsonNode[] {p.path("senderPn"), p.path("_data").path("senderPn"), p.path("author")}) {
            String w = LoggingEventListener.wid(n);
            if (w.endsWith("@c.us")) return w.substring(0, w.length() - "@c.us".length());
        }
        return null;
    }

    private static Instant messageTime(JsonNode p, WhatsAppEvent event) {
        long seconds = p.path("timestamp").asLong(0);
        if (seconds > 0) return Instant.ofEpochSecond(seconds);
        return event.ts() == null ? null : Instant.ofEpochMilli(event.ts());
    }
}
