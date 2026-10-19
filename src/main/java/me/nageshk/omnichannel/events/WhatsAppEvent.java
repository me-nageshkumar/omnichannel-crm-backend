package me.nageshk.omnichannel.events;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.ApplicationEvent;

/** An event emitted by the WhatsApp page of one user's extension (name = whatsapp-web.js event name). */
public class WhatsAppEvent extends ApplicationEvent {

    private final String username;
    private final String name;
    private final JsonNode payload;
    private final Long seq;
    private final Long ts;

    public WhatsAppEvent(Object source, String username, String name, JsonNode payload) {
        this(source, username, name, payload, null, null);
    }

    /** @param seq outbox sequence number for durable events (null for transient ones); @param ts capture time (ms epoch) */
    public WhatsAppEvent(Object source, String username, String name, JsonNode payload, Long seq, Long ts) {
        super(source);
        this.username = username;
        this.name = name;
        this.payload = payload;
        this.seq = seq;
        this.ts = ts;
    }

    public String username() {
        return username;
    }

    public String name() {
        return name;
    }

    public JsonNode payload() {
        return payload;
    }

    public Long seq() {
        return seq;
    }

    public Long ts() {
        return ts;
    }
}
