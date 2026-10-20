package me.nageshk.omnichannel.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import me.nageshk.omnichannel.contact.Contact;
import me.nageshk.omnichannel.contact.ContactRepository;
import me.nageshk.omnichannel.contact.ContactService;
import me.nageshk.omnichannel.contact.ContactSource;
import me.nageshk.omnichannel.events.WhatsAppEvent;
import me.nageshk.omnichannel.support.TestUsers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

@SpringBootTest
class WaWebInboxListenerIT {

    private static final String WS = "ws-1";
    private static final String CUSTOMER = "cust-1";
    private static final long T = 1_700_000_000L;

    /** Collects every Spring event the ingestion publishes. */
    static class Recorder {
        final List<Object> events = new CopyOnWriteArrayList<>();

        @EventListener
        void on(InboxEvents.ChatCreated e) {
            events.add(e);
        }

        @EventListener
        void on(InboxEvents.ChatReopened e) {
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

        <E> List<E> of(Class<E> type) {
            return events.stream().filter(type::isInstance).map(type::cast).toList();
        }
    }

    @TestConfiguration
    static class RecorderConfig {
        @Bean
        Recorder inboxRecorder() {
            return new Recorder();
        }
    }

    @Autowired ApplicationEventPublisher publisher;
    @Autowired ContactRepository contacts;
    @Autowired ContactService service;
    @Autowired WaWebChatRepository chats;
    @Autowired WaWebMessageRepository messages;
    @Autowired WaWebChatActionRepository actions;
    @Autowired MongoTemplate mongo;
    @Autowired TestUsers testUsers;
    @Autowired ObjectMapper mapper;
    @Autowired Recorder recorder;
    @Autowired Clock clock;

    @BeforeEach
    void setUp() {
        clean();
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        testUsers.deleteAll();
        for (Class<?> c : List.of(WaWebChat.class, WaWebMessage.class, WaWebChatAction.class))
            mongo.remove(new Query(), c);
        recorder.events.clear();
    }

    private ObjectNode message(String remote, boolean fromMe, long epochSeconds) {
        ObjectNode p = mapper.createObjectNode();
        ObjectNode id = p.putObject("id");
        id.put("fromMe", fromMe);
        id.put("remote", remote);
        id.put("id", "ABC" + epochSeconds);
        p.put("fromMe", fromMe);
        p.put("timestamp", epochSeconds);
        p.put("body", "hi");
        return p;
    }

    private void publish(String name, JsonNode payload) {
        publisher.publishEvent(new WhatsAppEvent(this, "pn-1", WS, CUSTOMER, name, payload, null, null));
    }

    private void create(JsonNode payload) {
        publish("message_create", payload);
    }

    private void ack(String waMessageId, int ack) {
        ObjectNode p = mapper.createObjectNode();
        p.putObject("message").putObject("id").put("_serialized", waMessageId);
        p.put("ack", ack);
        publish("message_ack", p);
    }

    private WaWebChat onlyChat() {
        assertThat(chats.count()).isEqualTo(1);
        return chats.findAll().getFirst();
    }

    private WaWebMessage onlyMessage() {
        assertThat(messages.count()).isEqualTo(1);
        return messages.findAll().getFirst();
    }

    // ---- chats and messages ----

    @Test
    void inboundMessageCreatesContactChatAndMessage() {
        create(message("919911111111@c.us", false, T));

        Contact contact = contacts.findByWorkspaceIdAndPhone(WS, "919911111111").orElseThrow();
        assertThat(contacts.count()).isEqualTo(1);
        WaWebChat chat = onlyChat();
        assertThat(chat.getContactId()).isEqualTo(contact.getId());
        assertThat(chat.getCustomerId()).isEqualTo(CUSTOMER);
        assertThat(chat.getWorkspaceId()).isEqualTo(WS);
        assertThat(chat.getPhoneNumberId()).isEqualTo("pn-1");
        assertThat(chat.getRemoteId()).isEqualTo("919911111111@c.us");
        assertThat(chat.getStatus()).isEqualTo(ChatStatus.OPEN);
        assertThat(chat.getUnreadCount()).isEqualTo(1);
        assertThat(chat.getLastMessage().text()).isEqualTo("hi");
        assertThat(chat.getLastMessage().direction()).isEqualTo(MessageDirection.IN);
        assertThat(chat.getLastMessage().type()).isEqualTo("chat");
        assertThat(chat.getLastMessage().status()).isEqualTo(MessageStatus.PENDING);
        assertThat(chat.getLastMessageAt()).isEqualTo(Instant.ofEpochSecond(T));
        assertThat(chat.getLastInboundAt()).isEqualTo(Instant.ofEpochSecond(T));
        assertThat(chat.getLastOutboundAt()).isNull();

        WaWebMessage m = onlyMessage();
        assertThat(m.getChatId()).isEqualTo(chat.getId());
        assertThat(m.getWaMessageId()).isEqualTo("false_919911111111@c.us_ABC" + T);
        assertThat(m.getDirection()).isEqualTo(MessageDirection.IN);
        assertThat(m.getSource()).isEqualTo(MessageSource.CONTACT);
        assertThat(m.getType()).isEqualTo("chat");
        assertThat(m.getBody()).isEqualTo("hi");
        assertThat(m.getStatus()).isEqualTo(MessageStatus.PENDING);
        assertThat(m.getAck()).isZero();
        assertThat(m.getTimestamp()).isEqualTo(Instant.ofEpochSecond(T));

        assertThat(recorder.of(InboxEvents.ChatCreated.class)).singleElement()
                .satisfies(e -> assertThat(e.firstDirection()).isEqualTo(MessageDirection.IN));
        assertThat(recorder.of(InboxEvents.MessageUpserted.class)).singleElement()
                .satisfies(e -> assertThat(e.inserted()).isTrue());
        assertThat(recorder.of(InboxEvents.ChatReopened.class)).isEmpty();
    }

    @Test
    void waMessageIdPrefersSerialized() {
        ObjectNode p = message("919911111111@c.us", false, T);
        ((ObjectNode) p.get("id")).put("_serialized", "false_919911111111@c.us_SER");
        create(p);
        assertThat(onlyMessage().getWaMessageId()).isEqualTo("false_919911111111@c.us_SER");
    }

    @Test
    void outgoingFromPhoneIsWhatsappWebSource() {
        create(message("919911111111@c.us", true, T));

        WaWebMessage m = onlyMessage();
        assertThat(m.getDirection()).isEqualTo(MessageDirection.OUT);
        assertThat(m.getSource()).isEqualTo(MessageSource.WHATSAPP_WEB);
        assertThat(m.getSentByUserId()).isNull();
        WaWebChat chat = onlyChat();
        assertThat(chat.getUnreadCount()).isZero();
        assertThat(chat.getLastOutboundAt()).isEqualTo(Instant.ofEpochSecond(T));
        assertThat(chat.getLastInboundAt()).isNull();
        assertThat(recorder.of(InboxEvents.ChatCreated.class)).singleElement()
                .satisfies(e -> assertThat(e.firstDirection()).isEqualTo(MessageDirection.OUT));
    }

    @Test
    void duplicateMessageCreateIsIdempotent() {
        ObjectNode p = message("919911111111@c.us", false, T);
        create(p);
        create(p);

        assertThat(messages.count()).isEqualTo(1);
        assertThat(onlyChat().getUnreadCount()).isEqualTo(1);
        assertThat(recorder.of(InboxEvents.ChatCreated.class)).hasSize(1);
        assertThat(recorder.of(InboxEvents.MessageUpserted.class)).hasSize(1);
    }

    @Test
    void secondMessageUpdatesTheSameChat() {
        create(message("919911111111@c.us", false, T));
        create(message("919911111111@c.us", true, T + 60));

        WaWebChat chat = onlyChat();
        assertThat(messages.count()).isEqualTo(2);
        assertThat(chat.getUnreadCount()).isEqualTo(1);
        assertThat(chat.getLastMessageAt()).isEqualTo(Instant.ofEpochSecond(T + 60));
        assertThat(chat.getLastMessage().direction()).isEqualTo(MessageDirection.OUT);
        assertThat(chat.getLastInboundAt()).isEqualTo(Instant.ofEpochSecond(T));
        assertThat(chat.getLastOutboundAt()).isEqualTo(Instant.ofEpochSecond(T + 60));
        assertThat(recorder.of(InboxEvents.ChatCreated.class)).hasSize(1);
    }

    @Test
    void olderMessageDoesNotReplaceThePreview() {
        ObjectNode newer = message("919911111111@c.us", false, T + 100);
        newer.put("body", "newer");
        create(newer);
        ObjectNode older = message("919911111111@c.us", false, T);
        older.put("body", "older");
        create(older);

        WaWebChat chat = onlyChat();
        assertThat(chat.getLastMessage().text()).isEqualTo("newer");
        assertThat(chat.getLastMessageAt()).isEqualTo(Instant.ofEpochSecond(T + 100));
        assertThat(chat.getUnreadCount()).isEqualTo(2);
    }

    @Test
    void missingTimestampFallsBackToEventTime() {
        ObjectNode p = message("919911111111@c.us", false, T);
        p.remove("timestamp");
        publisher.publishEvent(new WhatsAppEvent(this, "pn-1", WS, CUSTOMER, "message_create", p, null, 1_700_000_777_000L));
        assertThat(onlyMessage().getTimestamp()).isEqualTo(Instant.ofEpochMilli(1_700_000_777_000L));
    }

    @Test
    void messageEventIsIgnored() {
        publish("message", message("919911111111@c.us", false, T));
        assertThat(chats.count()).isZero();
        assertThat(messages.count()).isZero();
        assertThat(contacts.count()).isZero();
    }

    // ---- acks ----

    @Test
    void ackUpgradesStatusMonotonically() {
        ObjectNode p = message("919911111111@c.us", true, T);
        create(p);
        String id = onlyMessage().getWaMessageId();

        ack(id, 3);
        WaWebMessage m = onlyMessage();
        assertThat(m.getStatus()).isEqualTo(MessageStatus.READ);
        assertThat(m.getAck()).isEqualTo(3);
        assertThat(m.getStatusTimes().getReadAt()).isNotNull();
        assertThat(onlyChat().getLastMessage().status()).isEqualTo(MessageStatus.READ);

        Instant readAt = m.getStatusTimes().getReadAt();
        ack(id, 1);
        m = onlyMessage();
        assertThat(m.getStatus()).isEqualTo(MessageStatus.READ);
        assertThat(m.getStatusTimes().getSentAt()).isNull();
        assertThat(m.getStatusTimes().getReadAt()).isEqualTo(readAt);
        assertThat(onlyChat().getLastMessage().status()).isEqualTo(MessageStatus.READ);
    }

    @Test
    void ackPushesMessageAndChat() {
        create(message("919911111111@c.us", true, T));
        recorder.events.clear();
        ack(onlyMessage().getWaMessageId(), 2);

        assertThat(recorder.of(InboxEvents.MessageUpserted.class)).singleElement().satisfies(e -> {
            assertThat(e.inserted()).isFalse();
            assertThat(e.message().getStatus()).isEqualTo(MessageStatus.DELIVERED);
        });
        assertThat(recorder.of(InboxEvents.ChatChanged.class)).hasSize(1);
        assertThat(recorder.of(InboxEvents.ChatCreated.class)).isEmpty();
    }

    @Test
    void ackThatChangesNothingPushesNothing() {
        create(message("919911111111@c.us", true, T));
        String id = onlyMessage().getWaMessageId();
        ack(id, 3);
        recorder.events.clear();
        ack(id, 2);
        assertThat(recorder.events).isEmpty();
    }

    @Test
    void failedAckIsReachableFromAnyState() {
        create(message("919911111111@c.us", true, T));
        String id = onlyMessage().getWaMessageId();
        ack(id, 1);
        ack(id, -1);
        WaWebMessage m = onlyMessage();
        assertThat(m.getStatus()).isEqualTo(MessageStatus.FAILED);
        assertThat(m.getStatusTimes().getFailedAt()).isNotNull();
        ack(id, 3);
        assertThat(onlyMessage().getStatus()).isEqualTo(MessageStatus.FAILED);
    }

    @Test
    void ackOnOlderMessageLeavesThePreviewAlone() {
        create(message("919911111111@c.us", true, T));
        String first = onlyMessage().getWaMessageId();
        create(message("919911111111@c.us", true, T + 60));
        ack(first, 3);
        assertThat(onlyChat().getLastMessage().status()).isEqualTo(MessageStatus.PENDING);
    }

    @Test
    void ackForUnknownMessageIsIgnored() {
        ack("true_919911111111@c.us_NOPE", 3);
        assertThat(messages.count()).isZero();
        assertThat(chats.count()).isZero();
        assertThat(contacts.count()).isZero();
        assertThat(recorder.events).isEmpty();
    }

    @Test
    void ackBeforeMessageCreateDoesNotLeaveABodylessMessage() {
        ObjectNode p = message("919911111111@c.us", true, T);
        ack("true_919911111111@c.us_ABC" + T, 3);
        assertThat(messages.count()).isZero();
        create(p);
        assertThat(onlyMessage().getBody()).isEqualTo("hi");
    }

    // ---- reopen, filters ----

    @Test
    void inboundOnClosedChatReopens() {
        create(message("919911111111@c.us", false, T));
        WaWebChat chat = onlyChat();
        chat.setStatus(ChatStatus.CLOSED);
        chats.save(chat);
        recorder.events.clear();

        create(message("919911111111@c.us", false, T + 60));

        WaWebChat after = onlyChat();
        assertThat(after.getStatus()).isEqualTo(ChatStatus.OPEN);
        assertThat(after.getUnreadCount()).isEqualTo(2);
        assertThat(recorder.of(InboxEvents.ChatReopened.class)).singleElement()
                .satisfies(e -> assertThat(e.chat().getId()).isEqualTo(chat.getId()));
        assertThat(recorder.of(InboxEvents.ChatCreated.class)).isEmpty();
        List<WaWebChatAction> trail = actions.findAll();
        assertThat(trail).singleElement().satisfies(a -> {
            assertThat(a.getType()).isEqualTo(ActionType.REOPENED);
            assertThat(a.getChatId()).isEqualTo(chat.getId());
            assertThat(a.getByUserId()).isNull();
            assertThat(a.getWorkspaceId()).isEqualTo(WS);
            assertThat(a.getAt()).isNotNull();
        });
    }

    @Test
    void outboundOnClosedChatStaysClosed() {
        create(message("919911111111@c.us", false, T));
        WaWebChat chat = onlyChat();
        chat.setStatus(ChatStatus.CLOSED);
        chats.save(chat);
        recorder.events.clear();

        create(message("919911111111@c.us", true, T + 60));

        assertThat(onlyChat().getStatus()).isEqualTo(ChatStatus.CLOSED);
        assertThat(recorder.of(InboxEvents.ChatReopened.class)).isEmpty();
        assertThat(actions.count()).isZero();
    }

    @Test
    void groupsAndStatusAreIgnored() {
        create(message("123@g.us", false, T));
        create(message("status@broadcast", false, T));
        create(message("123456@broadcast", false, T));
        create(message("123456@newsletter", false, T));
        assertThat(chats.count()).isZero();
        assertThat(messages.count()).isZero();
        assertThat(contacts.count()).isZero();
    }

    @Test
    void invalidPhoneSkipsTheMessageEntirely() {
        create(message("not-a-number@c.us", false, T));
        assertThat(contacts.count()).isZero();
        assertThat(chats.count()).isZero();
        assertThat(messages.count()).isZero();
    }

    @Test
    void sameChatOnTwoNumbersMakesTwoChats() {
        ObjectNode p = message("919911111111@c.us", false, T);
        create(p);
        publisher.publishEvent(new WhatsAppEvent(this, "pn-2", WS, CUSTOMER, "message_create", p, null, null));
        assertThat(chats.count()).isEqualTo(2);
        assertThat(messages.count()).isEqualTo(2);
        assertThat(contacts.count()).isEqualTo(1);
    }

    // ---- media, location, labels ----

    @Test
    void mediaFieldsAreStored() {
        ObjectNode p = message("919911111111@c.us", false, T);
        p.put("body", "");
        p.put("hasMedia", true);
        p.put("type", "image");
        p.put("mimetype", "image/jpeg");
        p.putNull("filename");
        p.put("size", 1234);
        create(p);

        WaWebMessage m = onlyMessage();
        assertThat(m.getType()).isEqualTo("image");
        assertThat(m.getMedia().mimetype()).isEqualTo("image/jpeg");
        assertThat(m.getMedia().filename()).isNull();
        assertThat(m.getMedia().size()).isEqualTo(1234L);
        assertThat(m.getMedia().mediaId()).isNull();
        assertThat(onlyChat().getLastMessage().text()).isEqualTo("📷 Photo");
    }

    @Test
    void mediaFieldsFallBackToData() {
        ObjectNode p = message("919911111111@c.us", false, T);
        p.put("hasMedia", true);
        p.put("type", "document");
        p.put("body", "");
        p.putObject("_data").put("mimetype", "application/pdf").put("filename", "a.pdf").put("size", 99);
        create(p);

        WaWebMessage m = onlyMessage();
        assertThat(m.getMedia().mimetype()).isEqualTo("application/pdf");
        assertThat(m.getMedia().filename()).isEqualTo("a.pdf");
        assertThat(m.getMedia().size()).isEqualTo(99L);
        assertThat(onlyChat().getLastMessage().text()).isEqualTo("📄 a.pdf");
    }

    @Test
    void messageWithoutMediaHasNoMedia() {
        create(message("919911111111@c.us", false, T));
        assertThat(onlyMessage().getMedia()).isNull();
        assertThat(onlyMessage().getLocation()).isNull();
    }

    @Test
    void locationIsStored() {
        ObjectNode p = message("919911111111@c.us", false, T);
        p.put("type", "location");
        p.put("body", "");
        p.putObject("location").put("latitude", 12.5).put("longitude", 77.25).put("description", "Office");
        create(p);

        WaWebMessage.Location l = onlyMessage().getLocation();
        assertThat(l.latitude()).isEqualTo(12.5);
        assertThat(l.longitude()).isEqualTo(77.25);
        assertThat(l.description()).isEqualTo("Office");
        assertThat(onlyChat().getLastMessage().text()).isEqualTo("📍 Location");
    }

    @Test
    void locationFallsBackToData() {
        ObjectNode p = message("919911111111@c.us", false, T);
        p.put("type", "location");
        p.putObject("_data").put("lat", 1.5).put("lng", 2.5).put("loc", "Dock");
        create(p);
        WaWebMessage.Location l = onlyMessage().getLocation();
        assertThat(l.latitude()).isEqualTo(1.5);
        assertThat(l.longitude()).isEqualTo(2.5);
        assertThat(l.description()).isEqualTo("Dock");
    }

    @Test
    void longBodyIsCutInThePreviewOnly() {
        ObjectNode p = message("919911111111@c.us", false, T);
        p.put("body", "x".repeat(300));
        create(p);
        assertThat(onlyChat().getLastMessage().text()).hasSize(120);
        assertThat(onlyMessage().getBody()).hasSize(300);
    }

    @Test
    void previewLabelsByType() {
        assertThat(InboxIngestService.preview("image", null, null)).isEqualTo("📷 Photo");
        assertThat(InboxIngestService.preview("video", "", null)).isEqualTo("🎥 Video");
        assertThat(InboxIngestService.preview("audio", " ", null)).isEqualTo("🎤 Voice message");
        assertThat(InboxIngestService.preview("ptt", null, null)).isEqualTo("🎤 Voice message");
        assertThat(InboxIngestService.preview("document", null, "a.pdf")).isEqualTo("📄 a.pdf");
        assertThat(InboxIngestService.preview("document", null, null)).isEqualTo("📄 Document");
        assertThat(InboxIngestService.preview("sticker", null, null)).isEqualTo("Sticker");
        assertThat(InboxIngestService.preview("location", null, null)).isEqualTo("📍 Location");
        assertThat(InboxIngestService.preview("vcard", null, null)).isEqualTo("👤 Contact card");
        assertThat(InboxIngestService.preview("poll_creation", null, null)).isEqualTo("📊 Poll");
        assertThat(InboxIngestService.preview("revoked", null, null)).isEqualTo("This message was deleted");
        assertThat(InboxIngestService.preview("whatever", null, null)).isEqualTo("Unsupported message");
        assertThat(InboxIngestService.preview("multi_vcard", null, null)).isEqualTo("👤 Contact card");
        assertThat(InboxIngestService.preview("image", "caption", null)).isEqualTo("caption");
        assertThat(InboxIngestService.preview("document", "report", "a.pdf")).isEqualTo("report");
        assertThat(InboxIngestService.preview("chat", "hello", null)).isEqualTo("hello");
    }

    @Test
    void locationPreviewIgnoresTheThumbnailBody() {
        ObjectNode p = message("919911111111@c.us", false, T);
        p.put("type", "location");
        p.put("body", "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRof");
        p.putObject("location").put("latitude", 12.5).put("longitude", 77.25);
        create(p);
        assertThat(onlyChat().getLastMessage().text()).isEqualTo("📍 Location");
        assertThat(onlyMessage().getBody()).startsWith("/9j/");
    }

    @Test
    void vcardPreviewIgnoresTheCardText() {
        ObjectNode p = message("919911111111@c.us", false, T);
        p.put("type", "vcard");
        p.put("body", "BEGIN:VCARD\nVERSION:3.0\nFN:Asha\nEND:VCARD");
        create(p);
        assertThat(onlyChat().getLastMessage().text()).isEqualTo("👤 Contact card");
    }

    // ---- a failure midway through is retried without losing the summary or the events ----

    /** A repository that delegates to {@code real} but fails the {@code nth} call of {@code save} once. */
    @SuppressWarnings("unchecked")
    private <R> R failingSave(Class<R> type, R real, int nth, java.util.function.Consumer<Object> before,
            RuntimeException failure) {
        java.util.concurrent.atomic.AtomicInteger saves = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.stubbing.Answer<Object> delegate = org.mockito.AdditionalAnswers.delegatesTo(real);
        return mock(type, inv -> {
            if (inv.getMethod().getName().equals("save") && saves.incrementAndGet() == nth) {
                if (before != null) before.accept(inv.getArgument(0));
                throw failure;
            }
            return delegate.answer(inv);
        });
    }

    private List<Object> captureEvents(ApplicationEventPublisher capturing) {
        List<Object> seen = new CopyOnWriteArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> seen.add(inv.getArgument(0))).when(capturing).publishEvent(any(Object.class));
        return seen;
    }

    @Test
    void chatSaveConflictIsRetriedWithoutLosingTheSummaryOrEvents() {
        ApplicationEventPublisher capturing = mock(ApplicationEventPublisher.class);
        List<Object> seen = captureEvents(capturing);
        // save #1 creates the chat, save #2 is the summary update that conflicts once
        WaWebChatRepository flaky = failingSave(WaWebChatRepository.class, chats, 2, null,
                new org.springframework.dao.OptimisticLockingFailureException("stale"));
        InboxIngestService svc = new InboxIngestService(service, flaky, messages, actions, capturing, clock);

        svc.ingestMessage(new WhatsAppEvent(this, "pn-1", WS, CUSTOMER, "message_create",
                message("919911111111@c.us", false, T), null, null));

        WaWebChat chat = onlyChat();
        assertThat(chat.getUnreadCount()).isEqualTo(1);
        assertThat(chat.getLastMessage().text()).isEqualTo("hi");
        assertThat(chat.getLastInboundAt()).isEqualTo(Instant.ofEpochSecond(T));
        assertThat(messages.count()).isEqualTo(1);
        assertThat(seen.stream().filter(InboxEvents.ChatCreated.class::isInstance)).hasSize(1);
        assertThat(seen.stream().filter(InboxEvents.MessageUpserted.class::isInstance)).hasSize(1);
        assertThat(seen.stream().filter(InboxEvents.ChatChanged.class::isInstance)).hasSize(1);
    }

    @Test
    void closedChatReopenSurvivesAChatSaveConflict() {
        create(message("919911111111@c.us", false, T));
        WaWebChat closed = onlyChat();
        closed.setStatus(ChatStatus.CLOSED);
        chats.save(closed);
        ApplicationEventPublisher capturing = mock(ApplicationEventPublisher.class);
        List<Object> seen = captureEvents(capturing);
        WaWebChatRepository flaky = failingSave(WaWebChatRepository.class, chats, 1, null,
                new org.springframework.dao.OptimisticLockingFailureException("stale"));
        InboxIngestService svc = new InboxIngestService(service, flaky, messages, actions, capturing, clock);

        svc.ingestMessage(new WhatsAppEvent(this, "pn-1", WS, CUSTOMER, "message_create",
                message("919911111111@c.us", false, T + 60), null, null));

        assertThat(onlyChat().getStatus()).isEqualTo(ChatStatus.OPEN);
        assertThat(onlyChat().getUnreadCount()).isEqualTo(2);
        assertThat(seen.stream().filter(InboxEvents.ChatReopened.class::isInstance)).hasSize(1);
        assertThat(actions.count()).isEqualTo(1);
    }

    @Test
    void duplicateKeyOnMessageInsertStillPublishesChatCreatedOnce() {
        ApplicationEventPublisher capturing = mock(ApplicationEventPublisher.class);
        List<Object> seen = captureEvents(capturing);
        ObjectNode payload = message("919911111111@c.us", false, T);
        // another writer stores the same message just before our insert, which then hits the unique index
        WaWebMessageRepository racing = failingSave(WaWebMessageRepository.class, messages, 1,
                m -> messages.save(copyOf((WaWebMessage) m)),
                new org.springframework.dao.DuplicateKeyException("dup"));
        InboxIngestService svc = new InboxIngestService(service, chats, racing, actions, capturing, clock);

        svc.ingestMessage(new WhatsAppEvent(this, "pn-1", WS, CUSTOMER, "message_create", payload, null, null));

        assertThat(messages.count()).isEqualTo(1);
        assertThat(onlyChat().getUnreadCount()).isEqualTo(1);
        assertThat(seen.stream().filter(InboxEvents.ChatCreated.class::isInstance)).hasSize(1);
        assertThat(seen.stream().filter(InboxEvents.MessageUpserted.class::isInstance)).hasSize(1);
        assertThat(seen.stream().filter(InboxEvents.ChatChanged.class::isInstance)).hasSize(1);
    }

    private static WaWebMessage copyOf(WaWebMessage m) {
        WaWebMessage c = new WaWebMessage();
        c.setCustomerId(m.getCustomerId());
        c.setWorkspaceId(m.getWorkspaceId());
        c.setPhoneNumberId(m.getPhoneNumberId());
        c.setChatId(m.getChatId());
        c.setWaMessageId(m.getWaMessageId());
        c.setDirection(m.getDirection());
        c.setSource(m.getSource());
        c.setType(m.getType());
        c.setBody(m.getBody());
        c.setTimestamp(m.getTimestamp());
        c.setStatus(m.getStatus());
        c.setAck(m.getAck());
        return c;
    }

    // ---- contacts (moved from ChatContactListenerIT) ----

    @Test
    void incomingCreatesContactWithName() {
        ObjectNode p = message("919812345678@c.us", false, T);
        p.put("notifyName", "Asha Rao");
        create(p);

        Contact c = contacts.findByWorkspaceIdAndPhone(WS, "919812345678").orElseThrow();
        assertThat(c.getName()).isEqualTo("Asha Rao");
        assertThat(c.getWaId()).isEqualTo("919812345678@c.us");
        assertThat(c.getSource()).isEqualTo(ContactSource.CHAT);
        assertThat(c.getCustomerId()).isEqualTo(CUSTOMER);
        assertThat(c.isNameEdited()).isFalse();
        assertThat(c.getLastMessageAt()).isEqualTo(Instant.ofEpochSecond(T));
    }

    @Test
    void nameFallsBackToDataNotifyNameThenPushname() {
        ObjectNode p = message("919812345678@c.us", false, T);
        p.putObject("_data").put("notifyName", "From Data");
        create(p);
        assertThat(contacts.findByWorkspaceIdAndPhone(WS, "919812345678").orElseThrow().getName())
                .isEqualTo("From Data");

        ObjectNode q = message("919812345679@c.us", false, T);
        q.put("notifyName", "  ");
        q.put("pushname", "Push");
        create(q);
        assertThat(contacts.findByWorkspaceIdAndPhone(WS, "919812345679").orElseThrow().getName()).isEqualTo("Push");
    }

    @Test
    void outgoingCreatesContactWithoutName() {
        ObjectNode p = message("919812345678@c.us", true, T);
        p.put("notifyName", "Me Myself");
        create(p);

        Contact c = contacts.findByWorkspaceIdAndPhone(WS, "919812345678").orElseThrow();
        assertThat(c.getName()).isNull();
        assertThat(c.getSource()).isEqualTo(ContactSource.CHAT);
    }

    @Test
    void groupIsIgnored() {
        create(message("120363000000000000@g.us", false, T));
        create(message("status@broadcast", false, T));
        create(message("123456@broadcast", false, T));
        create(message("123456@newsletter", false, T));
        assertThat(contacts.count()).isZero();
    }

    @Test
    void otherEventsAreIgnored() {
        publish("message_ack", message("919812345678@c.us", false, T));
        assertThat(contacts.count()).isZero();
    }

    @Test
    void remoteMayBeAWidObject() {
        ObjectNode p = message("x", false, T);
        ObjectNode remote = ((ObjectNode) p.get("id")).putObject("remote");
        remote.put("server", "c.us").put("user", "919812345678").put("_serialized", "919812345678@c.us");
        create(p);
        assertThat(contacts.findByWorkspaceIdAndPhone(WS, "919812345678")).isPresent();
        assertThat(onlyChat().getRemoteId()).isEqualTo("919812345678@c.us");
    }

    @Test
    void lidCreatesHiddenContact() {
        ObjectNode p = message("123456789012345@lid", false, T);
        p.put("notifyName", "Hidden Hari");
        create(p);

        Contact c = contacts.findByWorkspaceIdAndWaId(WS, "123456789012345@lid").orElseThrow();
        assertThat(c.getPhone()).isNull();
        assertThat(c.getName()).isEqualTo("Hidden Hari");
        assertThat(c.getSource()).isEqualTo(ContactSource.CHAT);
        assertThat(onlyChat().getContactId()).isEqualTo(c.getId());
    }

    @Test
    void lidWithSenderPhoneLinksContact() {
        ObjectNode p = message("123456789012345@lid", false, T);
        p.put("senderPn", "919812345678@c.us");
        create(p);
        Contact c = contacts.findByWorkspaceIdAndWaId(WS, "123456789012345@lid").orElseThrow();
        assertThat(c.getPhone()).isEqualTo("919812345678");
        WaWebChat chat = onlyChat();
        assertThat(chat.getRemoteId()).isEqualTo("123456789012345@lid");
        assertThat(chat.getContactId()).isEqualTo(c.getId());

        ObjectNode q = message("222222222222222@lid", false, T);
        q.putObject("_data").put("senderPn", "919812345600@c.us");
        create(q);
        assertThat(contacts.findByWorkspaceIdAndWaId(WS, "222222222222222@lid").orElseThrow().getPhone())
                .isEqualTo("919812345600");

        ObjectNode r = message("333333333333333@lid", false, T);
        r.put("author", "919812345611@c.us");
        create(r);
        assertThat(contacts.findByWorkspaceIdAndWaId(WS, "333333333333333@lid").orElseThrow().getPhone())
                .isEqualTo("919812345611");
    }

    @Test
    void lidPhoneAlreadyUsedStaysHidden() {
        Contact existing = new Contact();
        existing.setWorkspaceId(WS);
        existing.setPhone("919812345678");
        existing.setSource(ContactSource.IMPORT);
        contacts.save(existing);

        ObjectNode p = message("123456789012345@lid", false, T);
        p.put("senderPn", "919812345678@c.us");
        create(p);

        assertThat(contacts.count()).isEqualTo(2);
        assertThat(contacts.findByWorkspaceIdAndWaId(WS, "123456789012345@lid").orElseThrow().getPhone()).isNull();
    }

    @Test
    void chatLinksImportedContactByPhone() {
        Contact imported = new Contact();
        imported.setWorkspaceId(WS);
        imported.setCustomerId(CUSTOMER);
        imported.setPhone("919812345678");
        imported.setName("Imported Ira");
        imported.setNameEdited(true);
        imported.setSource(ContactSource.IMPORT);
        imported = contacts.save(imported);

        create(message("919812345678@c.us", false, T));

        assertThat(contacts.count()).isEqualTo(1);
        Contact c = contacts.findById(imported.getId()).orElseThrow();
        assertThat(c.getWaId()).isEqualTo("919812345678@c.us");
        assertThat(c.getSource()).isEqualTo(ContactSource.IMPORT);
        assertThat(c.getLastMessageAt()).isEqualTo(Instant.ofEpochSecond(T));
        assertThat(onlyChat().getContactId()).isEqualTo(imported.getId());
    }

    @Test
    void editedNameIsNotOverwritten() {
        Contact typed = new Contact();
        typed.setWorkspaceId(WS);
        typed.setPhone("919812345678");
        typed.setName("Typed Name");
        typed.setNameEdited(true);
        typed.setSource(ContactSource.MANUAL);
        contacts.save(typed);

        ObjectNode p = message("919812345678@c.us", false, T);
        p.put("notifyName", "Push Name");
        create(p);
        assertThat(contacts.findByWorkspaceIdAndPhone(WS, "919812345678").orElseThrow().getName())
                .isEqualTo("Typed Name");

        // a name that was never edited follows the sender's display name
        ObjectNode q = message("919812345600@c.us", false, T);
        q.put("notifyName", "First");
        create(q);
        ObjectNode r = message("919812345600@c.us", false, T + 100);
        r.put("notifyName", "Second");
        create(r);
        assertThat(contacts.findByWorkspaceIdAndPhone(WS, "919812345600").orElseThrow().getName()).isEqualTo("Second");
    }

    @Test
    void replayIsIdempotent() {
        ObjectNode p = message("919812345678@c.us", false, T);
        p.put("notifyName", "Asha");
        create(p);
        Contact first = contacts.findByWorkspaceIdAndPhone(WS, "919812345678").orElseThrow();
        create(p);
        create(p);

        assertThat(contacts.count()).isEqualTo(1);
        Contact again = contacts.findByWorkspaceIdAndPhone(WS, "919812345678").orElseThrow();
        assertThat(again.getVersion()).isEqualTo(first.getVersion());
        assertThat(chats.count()).isEqualTo(1);
        assertThat(messages.count()).isEqualTo(1);
    }

    @Test
    void sameChatInTwoBrandsMakesTwoContacts() {
        ObjectNode p = message("919812345678@c.us", false, T);
        create(p);
        publisher.publishEvent(new WhatsAppEvent(this, "pn-2", "ws-2", "cust-2", "message_create", p, null, null));
        assertThat(contacts.count()).isEqualTo(2);
    }

    @Test
    void lastMessageAtNeverGoesBack() {
        create(message("919812345678@c.us", false, 1_700_000_500L));
        create(message("919812345678@c.us", false, 1_700_000_100L));
        assertThat(contacts.findByWorkspaceIdAndPhone(WS, "919812345678").orElseThrow().getLastMessageAt())
                .isEqualTo(Instant.ofEpochSecond(1_700_000_500L));
        create(message("919812345678@c.us", false, 1_700_000_900L));
        assertThat(contacts.findByWorkspaceIdAndPhone(WS, "919812345678").orElseThrow().getLastMessageAt())
                .isEqualTo(Instant.ofEpochSecond(1_700_000_900L));
    }

    @Test
    void listenerSwallowsErrors() {
        // a null payload never reaches the bus (LoggingEventListener would fail on it), so call the listener directly
        WaWebInboxListener real = new WaWebInboxListener(
                new InboxIngestService(service, chats, messages, actions, publisher, clock));
        assertThatCode(() -> real.on(new WhatsAppEvent(this, "pn-1", WS, CUSTOMER, "message_create", null, null, null)))
                .doesNotThrowAnyException();
        assertThatCode(() -> real.on(new WhatsAppEvent(this, "pn-1", WS, CUSTOMER, "message_ack", null, null, null)))
                .doesNotThrowAnyException();
        assertThatCode(() -> create(mapper.createObjectNode())).doesNotThrowAnyException();
        assertThatCode(() -> create(mapper.createArrayNode())).doesNotThrowAnyException();
        assertThatCode(() -> create(message("not-a-number@c.us", false, 1L))).doesNotThrowAnyException();
        assertThat(contacts.count()).isZero();
        assertThat(chats.count()).isZero();

        ContactService failing = mock(ContactService.class);
        doThrow(new IllegalStateException("db down")).when(failing)
                .upsertFromChat(any(), any(), any(), any(), any(), any());
        WaWebInboxListener listener = new WaWebInboxListener(
                new InboxIngestService(failing, chats, messages, actions, publisher, clock));
        WhatsAppEvent event = new WhatsAppEvent(this, "pn-1", WS, CUSTOMER, "message_create",
                message("919812345678@c.us", false, T), null, null);
        assertThatCode(() -> listener.on(event)).doesNotThrowAnyException();
        assertThat(chats.count()).isZero();
    }

    @Test
    void failureInAnEventSubscriberDoesNotEscape() {
        ApplicationEventPublisher boom = mock(ApplicationEventPublisher.class);
        doThrow(new IllegalStateException("subscriber failed")).when(boom).publishEvent(any(Object.class));
        WaWebInboxListener listener = new WaWebInboxListener(
                new InboxIngestService(service, chats, messages, actions, boom, clock));
        WhatsAppEvent event = new WhatsAppEvent(this, "pn-1", WS, CUSTOMER, "message_create",
                message("919812345678@c.us", false, T), null, null);
        assertThatCode(() -> listener.on(event)).doesNotThrowAnyException();
    }
}
