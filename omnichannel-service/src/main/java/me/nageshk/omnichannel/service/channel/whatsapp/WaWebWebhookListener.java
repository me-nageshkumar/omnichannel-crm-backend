package me.nageshk.omnichannel.service.channel.whatsapp;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import me.nageshk.omnichannel.common.channel.ChannelContext;
import me.nageshk.omnichannel.common.channel.ChannelKind;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.WebhookEvent;
import me.nageshk.omnichannel.common.events.IngestionFailedException;
import me.nageshk.omnichannel.db.phone.PhoneNumber;
import me.nageshk.omnichannel.db.phone.PhoneNumberRepository;
import me.nageshk.omnichannel.service.channel.ChannelWebhookEventStore;
import me.nageshk.omnichannel.service.events.WhatsAppEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Turns the conversation events of a WhatsApp Web number into Cloud API shaped webhook events, keeps each one, and
 * hands it to the {@link WhatsAppWebhookProcessor}. Synchronous on the publishing thread: an
 * {@link IngestionFailedException} (the database failed) reaches the publisher so the event is not acknowledged and
 * is replayed; anything else wrong with an event is logged and the event acknowledged, since a replay would fail the
 * same way. {@code message_create} fires for both directions, so {@code message} (inbound only) is ignored; device
 * events ({@code ready}, {@code change_state}, {@code disconnected}) are not conversation events.
 */
@Component
public class WaWebWebhookListener {

    private static final Logger log = LoggerFactory.getLogger(WaWebWebhookListener.class);
    private static final Set<String> CONVERSATION_EVENTS = Set.of("message_create", "message_ack", "vote_update");

    private final WaWebWebhookConverter converter;
    private final ChannelWebhookEventStore store;
    private final WhatsAppWebhookProcessor processor;
    private final PhoneNumberRepository phoneNumbers;
    /** The digits of each number, without the {@code +}; a number never changes once onboarded. */
    private final Map<String, String> digits = new ConcurrentHashMap<>();

    public WaWebWebhookListener(WaWebWebhookConverter converter, ChannelWebhookEventStore store,
            WhatsAppWebhookProcessor processor, PhoneNumberRepository phoneNumbers) {
        this.converter = converter;
        this.store = store;
        this.processor = processor;
        this.phoneNumbers = phoneNumbers;
    }

    @EventListener
    public void on(WhatsAppEvent event) {
        if (!CONVERSATION_EVENTS.contains(event.name())) return;
        ChannelContext ctx = new ChannelContext(ChannelKind.WHATSAPP_WEB, event.customerId(), event.workspaceId(),
                event.phoneNumberId(), digitsOf(event.phoneNumberId()));
        Instant capturedAt = event.ts() == null ? null : Instant.ofEpochMilli(event.ts());

        Optional<WebhookEvent> converted;
        try {
            converted = converter.convert(ctx, event.name(), event.payload(), capturedAt);
        } catch (RuntimeException e) {
            log.warn("could not convert {} event {} of {} (fields {}): {}", event.name(), idOf(event),
                    event.phoneNumberId(), fieldsOf(event.payload()), e.toString());
            save(ctx, null, event, capturedAt);
            return;
        }
        if (converted.isEmpty()) {
            // a notice, a group or broadcast chat, an ack of a person's message: nothing for the inbox
            log.debug("no webhook for {} event {} of {}", event.name(), idOf(event), event.phoneNumberId());
            return;
        }
        save(ctx, converted.get(), event, capturedAt);

        try {
            processor.process(ctx, converted.get());
        } catch (IngestionFailedException e) {
            // the database failed: the event must not be acknowledged, so the extension replays it
            throw e;
        } catch (RuntimeException e) {
            log.warn("could not ingest {} event {} of {}: {}", event.name(), idOf(event), event.phoneNumberId(),
                    e.toString());
        }
    }

    /** Best effort: the stored event is for debugging and replay, the message is what matters. */
    private void save(ChannelContext ctx, WebhookEvent webhook, WhatsAppEvent event, Instant capturedAt) {
        try {
            store.save(ctx, webhook, event.payload(), event.seq(), capturedAt);
        } catch (RuntimeException e) {
            log.warn("could not keep {} event {} of {}: {}", event.name(), idOf(event), event.phoneNumberId(),
                    e.toString());
        }
    }

    /** Null when the number is unknown or cannot be read now; only a found number is remembered. */
    private String digitsOf(String phoneNumberId) {
        if (phoneNumberId == null) return null;
        String known = digits.get(phoneNumberId);
        if (known != null) return known;
        try {
            String number = phoneNumbers.findById(phoneNumberId).map(PhoneNumber::getNumber).orElse(null);
            if (number == null) return null;
            String d = number.startsWith("+") ? number.substring(1) : number;
            digits.put(phoneNumberId, d);
            return d;
        } catch (RuntimeException e) {
            log.warn("could not read number {}: {}", phoneNumberId, e.toString());
            return null;
        }
    }

    /** The message the event is about, for the log. */
    private static String idOf(WhatsAppEvent event) {
        JsonNode p = event.payload();
        if (p == null || !p.isObject()) return "?";
        String id = switch (event.name()) {
            case "message_ack" -> WaIds.messageId(p.path("message").path("id"));
            case "vote_update" -> p.path("pollMessageId").asString("");
            default -> WaIds.messageId(p.path("id"));
        };
        return id.isEmpty() ? "?" : id;
    }

    private static Set<String> fieldsOf(JsonNode payload) {
        return payload == null || !payload.isObject() ? Set.of() : new TreeSet<>(payload.propertyNames());
    }
}
