package in.redlava.wabridge.events;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.ApplicationEvent;

/** An event emitted by the WhatsApp page of one user's extension (name = whatsapp-web.js event name). */
public class WhatsAppEvent extends ApplicationEvent {

    private final String username;
    private final String name;
    private final JsonNode payload;

    public WhatsAppEvent(Object source, String username, String name, JsonNode payload) {
        super(source);
        this.username = username;
        this.name = name;
        this.payload = payload;
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
}
