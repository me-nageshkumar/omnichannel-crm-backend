package me.nageshk.omnichannel.events;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.ApplicationEvent;

/**
 * An event emitted by the WhatsApp Web page of one onboarded number (name = whatsapp-web.js event
 * name), published only once the page's account is confirmed to be that number.
 */
public class WhatsAppEvent extends ApplicationEvent {

    private final String phoneNumberId;
    private final String workspaceId;
    private final String customerId;
    private final String name;
    private final JsonNode payload;
    private final Long seq;
    private final Long ts;

    /** @param seq outbox sequence number for durable events (null for transient ones); @param ts capture time (ms epoch) */
    public WhatsAppEvent(Object source, String phoneNumberId, String workspaceId, String customerId, String name,
            JsonNode payload, Long seq, Long ts) {
        super(source);
        this.phoneNumberId = phoneNumberId;
        this.workspaceId = workspaceId;
        this.customerId = customerId;
        this.name = name;
        this.payload = payload;
        this.seq = seq;
        this.ts = ts;
    }

    public String phoneNumberId() { return phoneNumberId; }
    public String workspaceId() { return workspaceId; }
    public String customerId() { return customerId; }
    public String name() { return name; }
    public JsonNode payload() { return payload; }
    public Long seq() { return seq; }
    public Long ts() { return ts; }
}
