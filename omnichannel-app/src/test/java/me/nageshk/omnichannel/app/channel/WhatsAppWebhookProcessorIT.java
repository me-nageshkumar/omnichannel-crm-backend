package me.nageshk.omnichannel.app.channel;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import me.nageshk.omnichannel.common.channel.ChannelContext;
import me.nageshk.omnichannel.common.channel.ChannelKind;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.WebhookEvent;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.inbox.ChatStatus;
import me.nageshk.omnichannel.db.inbox.MessageDirection;
import me.nageshk.omnichannel.db.inbox.MessageSource;
import me.nageshk.omnichannel.db.inbox.MessageStatus;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatAction;
import me.nageshk.omnichannel.db.inbox.WaWebChatRepository;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.db.inbox.WaWebMessageRepository;
import me.nageshk.omnichannel.service.channel.whatsapp.WhatsAppWebhookProcessor;
import me.nageshk.omnichannel.service.inbox.InboxEvents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
class WhatsAppWebhookProcessorIT {

    private static final ChannelContext CTX =
            new ChannelContext(ChannelKind.WHATSAPP_WEB, "cust-1", "ws-1", "pn-1", "919928088743");
    private static final long T = 1_793_872_800L;

    /** Collects the inbox events, in the order they were published. */
    static class Recorder {
        final List<Object> events = new CopyOnWriteArrayList<>();

        @EventListener
        void on(InboxEvents.ChatCreated e) {
            events.add(e);
        }

        @EventListener
        void on(InboxEvents.MessageUpserted e) {
            events.add(e);
        }

        @EventListener
        void on(InboxEvents.ChatChanged e) {
            events.add(e);
        }
    }

    @TestConfiguration
    static class RecorderConfig {
        @Bean
        Recorder processorRecorder() {
            return new Recorder();
        }
    }

    @Autowired WhatsAppWebhookProcessor processor;
    @Autowired WaWebChatRepository chats;
    @Autowired WaWebMessageRepository messages;
    @Autowired MongoTemplate mongo;
    @Autowired ObjectMapper mapper;
    @Autowired Recorder recorder;

    @BeforeEach
    @AfterEach
    void clean() {
        for (Class<?> c : List.of(WaWebChat.class, WaWebMessage.class, WaWebChatAction.class, Contact.class))
            mongo.remove(new Query(), c);
        recorder.events.clear();
    }

    private WebhookEvent read(String name) {
        try (InputStream in = getClass().getResourceAsStream("/webhook/meta/" + name)) {
            return mapper.readValue(in, WebhookEvent.class);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private WebhookEvent event(String json) {
        return mapper.readValue(json, WebhookEvent.class);
    }

    /** Our text to 919912345678 as an echo, and in the same entry the status its ack gives. */
    private WebhookEvent echoWithStatus(String id, int ack) {
        String status = switch (ack) {
            case 1 -> "sent";
            case 2 -> "delivered";
            case 3 -> "read";
            default -> throw new IllegalArgumentException("ack " + ack);
        };
        return event("""
                {"object": "whatsapp_business_account", "entry": [{"id": "pn-1", "changes": [
                  {"field": "smb_message_echoes", "value": {"messaging_product": "whatsapp",
                    "metadata": {"display_phone_number": "919928088743", "phone_number_id": "pn-1"},
                    "message_echoes": [{"from": "919928088743", "to": "919912345678", "id": "%1$s",
                      "timestamp": "%2$d", "type": "text", "text": {"body": "Hello"},
                      "wa_web": {"chat_id": "919912345678@c.us", "ack": %3$d}}]}},
                  {"field": "messages", "value": {"messaging_product": "whatsapp",
                    "metadata": {"display_phone_number": "919928088743", "phone_number_id": "pn-1"},
                    "statuses": [{"id": "%1$s", "status": "%4$s", "timestamp": "%2$d",
                      "recipient_id": "919912345678", "wa_web": {"chat_id": "919912345678@c.us", "ack": %3$d}}]}}
                ]}]}""".formatted(id, T, ack, status));
    }

    private WebhookEvent statusOnly(String id, String status) {
        return event("""
                {"object": "whatsapp_business_account", "entry": [{"id": "pn-1", "changes": [
                  {"field": "messages", "value": {"messaging_product": "whatsapp",
                    "statuses": [{"id": "%s", "status": "%s", "timestamp": "%d", "recipient_id": "919912345678",
                      "wa_web": {"chat_id": "919912345678@c.us"}}]}}]}]}""".formatted(id, status, T));
    }

    @Test
    void aRealMetaInboundTextIsStoredAsAMessageFromAPhoneChat() {
        processor.process(CTX, read("text_inbound.json"));

        WaWebChat chat = chats.findByPhoneNumberIdAndRemoteId("pn-1", "919928088743@c.us").orElseThrow();
        WaWebMessage m = messages.findByPhoneNumberIdAndWaMessageId("pn-1",
                "wamid.HBgMOTE5OTI4MDg4NzQzFQIAEhgUM0EwMUMyQjk2NDBDMTE5N0Y3RkQA").orElseThrow();
        assertThat(m.getType()).isEqualTo("text");
        assertThat(m.getBody()).isEqualTo("Hi");
        assertThat(m.getDirection()).isEqualTo(MessageDirection.IN);
        assertThat(m.getStatus()).isEqualTo(MessageStatus.PENDING);
        assertThat(m.getAck()).isZero();
        assertThat(m.getTimestamp()).isEqualTo(Instant.ofEpochSecond(1_721_738_362L));
        assertThat(chat.getUnreadCount()).isEqualTo(1);
        assertThat(mongo.findAll(Contact.class)).singleElement()
                .satisfies(c -> assertThat(c.getName()).isEqualTo("Nagesh Kumar"));
    }

    @Test
    void anEchoAndItsStatusInOneEntryAreOneWrite() {
        processor.process(CTX, echoWithStatus("true_919912345678@c.us_E1", 2));

        assertThat(messages.findByPhoneNumberIdAndWaMessageId("pn-1", "true_919912345678@c.us_E1"))
                .hasValueSatisfying(m -> {
                    assertThat(m.getDirection()).isEqualTo(MessageDirection.OUT);
                    assertThat(m.getStatus()).isEqualTo(MessageStatus.DELIVERED);
                    assertThat(m.getAck()).isEqualTo(2);
                    assertThat(m.getStatusTimes().getDeliveredAt()).isNotNull();
                });
        assertThat(recorder.events).filteredOn(e -> e instanceof InboxEvents.MessageUpserted).singleElement()
                .satisfies(e -> assertThat(((InboxEvents.MessageUpserted) e).inserted()).isTrue());
    }

    @Test
    void anEchoForAMessageTheConsoleStoredAppliesTheStatusOnce() {
        WaWebChat chat = new WaWebChat();
        chat.setCustomerId("cust-1");
        chat.setWorkspaceId("ws-1");
        chat.setPhoneNumberId("pn-1");
        chat.setRemoteId("919912345678@c.us");
        chat.setStatus(ChatStatus.OPEN);
        chat.setLastMessageAt(Instant.ofEpochSecond(T));
        chat.setLastMessage(new WaWebChat.LastMessage(MessageDirection.OUT, "text", "Hello", MessageStatus.SENT));
        chat = chats.save(chat);
        WaWebMessage sent = new WaWebMessage();
        sent.setCustomerId("cust-1");
        sent.setWorkspaceId("ws-1");
        sent.setPhoneNumberId("pn-1");
        sent.setChatId(chat.getId());
        sent.setWaMessageId("true_919912345678@c.us_C1");
        sent.setDirection(MessageDirection.OUT);
        sent.setSource(MessageSource.CONSOLE);
        sent.setType("text");
        sent.setBody("Hello");
        sent.setTimestamp(Instant.ofEpochSecond(T));
        sent.setStatus(MessageStatus.SENT);
        sent.setAck(1);
        messages.save(sent);

        processor.process(CTX, echoWithStatus("true_919912345678@c.us_C1", 2));

        assertThat(messages.count()).isEqualTo(1);
        WaWebMessage m = messages.findAll().getFirst();
        assertThat(m.getSource()).isEqualTo(MessageSource.CONSOLE);
        assertThat(m.getStatus()).isEqualTo(MessageStatus.DELIVERED);
        assertThat(chats.findById(chat.getId()).orElseThrow().getLastMessage().status())
                .isEqualTo(MessageStatus.DELIVERED);
        assertThat(recorder.events).filteredOn(e -> e instanceof InboxEvents.MessageUpserted).singleElement()
                .satisfies(e -> assertThat(((InboxEvents.MessageUpserted) e).inserted()).isFalse());
        assertThat(recorder.events).filteredOn(e -> e instanceof InboxEvents.ChatChanged).hasSize(1);
        assertThat(recorder.events).noneMatch(e -> e instanceof InboxEvents.ChatCreated);
    }

    @Test
    void aStatusForAnUnknownMessageIsDropped() {
        processor.process(CTX, statusOnly("true_919912345678@c.us_NOPE", "read"));

        assertThat(messages.count()).isZero();
        assertThat(chats.count()).isZero();
        assertThat(mongo.count(new Query(), Contact.class)).isZero();
        assertThat(recorder.events).isEmpty();
    }

    @Test
    void aStatusBeforeItsMessageIsDroppedAndTheEchoStillStoresTheMessage() {
        processor.process(CTX, statusOnly("true_919912345678@c.us_E2", "read"));
        assertThat(messages.count()).isZero();

        processor.process(CTX, echoWithStatus("true_919912345678@c.us_E2", 1));

        assertThat(messages.findByPhoneNumberIdAndWaMessageId("pn-1", "true_919912345678@c.us_E2"))
                .hasValueSatisfying(m -> {
                    assertThat(m.getBody()).isEqualTo("Hello");
                    assertThat(m.getStatus()).isEqualTo(MessageStatus.SENT);
                });
    }

    @Test
    void aNewInboundChatPublishesCreatedThenUpsertedThenChanged() {
        processor.process(CTX, read("text_inbound.json"));

        List<Class<?>> order = recorder.events.stream().<Class<?>>map(Object::getClass).toList();
        assertThat(order.getFirst()).isEqualTo(InboxEvents.ChatCreated.class);
        assertThat(order).containsSubsequence(InboxEvents.ChatCreated.class, InboxEvents.MessageUpserted.class,
                InboxEvents.ChatChanged.class);
        assertThat(order.getLast()).isEqualTo(InboxEvents.ChatChanged.class);
    }
}
