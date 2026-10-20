package me.nageshk.omnichannel.contact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import me.nageshk.omnichannel.events.WhatsAppEvent;
import me.nageshk.omnichannel.support.TestUsers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;

@SpringBootTest
class ChatContactListenerIT {

    private static final String WS = "ws-1";
    private static final String CUSTOMER = "cust-1";

    @Autowired ApplicationEventPublisher publisher;
    @Autowired ContactRepository contacts;
    @Autowired TestUsers testUsers;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        testUsers.deleteAll();
    }

    @AfterEach
    void clean() {
        testUsers.deleteAll();
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

    @Test
    void incomingCreatesContactWithName() {
        ObjectNode p = message("919812345678@c.us", false, 1_700_000_000L);
        p.put("notifyName", "Asha Rao");
        publish("message", p);

        Contact c = contacts.findByWorkspaceIdAndPhone(WS, "919812345678").orElseThrow();
        assertThat(c.getName()).isEqualTo("Asha Rao");
        assertThat(c.getWaId()).isEqualTo("919812345678@c.us");
        assertThat(c.getSource()).isEqualTo(ContactSource.CHAT);
        assertThat(c.getCustomerId()).isEqualTo(CUSTOMER);
        assertThat(c.isNameEdited()).isFalse();
        assertThat(c.getLastMessageAt()).isEqualTo(Instant.ofEpochSecond(1_700_000_000L));
    }

    @Test
    void nameFallsBackToDataNotifyNameThenPushname() {
        ObjectNode p = message("919812345678@c.us", false, 1_700_000_000L);
        p.putObject("_data").put("notifyName", "From Data");
        publish("message", p);
        assertThat(contacts.findByWorkspaceIdAndPhone(WS, "919812345678").orElseThrow().getName())
                .isEqualTo("From Data");

        ObjectNode q = message("919812345679@c.us", false, 1_700_000_000L);
        q.put("notifyName", "  ");
        q.put("pushname", "Push");
        publish("message", q);
        assertThat(contacts.findByWorkspaceIdAndPhone(WS, "919812345679").orElseThrow().getName()).isEqualTo("Push");
    }

    @Test
    void outgoingCreatesContactWithoutName() {
        ObjectNode p = message("919812345678@c.us", true, 1_700_000_000L);
        p.put("notifyName", "Me Myself");
        publish("message_create", p);

        Contact c = contacts.findByWorkspaceIdAndPhone(WS, "919812345678").orElseThrow();
        assertThat(c.getName()).isNull();
        assertThat(c.getSource()).isEqualTo(ContactSource.CHAT);
    }

    @Test
    void groupIsIgnored() {
        publish("message", message("120363000000000000@g.us", false, 1_700_000_000L));
        publish("message", message("status@broadcast", false, 1_700_000_000L));
        publish("message", message("123456@broadcast", false, 1_700_000_000L));
        publish("message", message("123456@newsletter", false, 1_700_000_000L));
        assertThat(contacts.count()).isZero();
    }

    @Test
    void otherEventsAreIgnored() {
        publish("message_ack", message("919812345678@c.us", false, 1_700_000_000L));
        assertThat(contacts.count()).isZero();
    }

    @Test
    void remoteMayBeAWidObject() {
        ObjectNode p = message("x", false, 1_700_000_000L);
        ObjectNode remote = ((ObjectNode) p.get("id")).putObject("remote");
        remote.put("server", "c.us").put("user", "919812345678").put("_serialized", "919812345678@c.us");
        publish("message", p);
        assertThat(contacts.findByWorkspaceIdAndPhone(WS, "919812345678")).isPresent();
    }

    @Test
    void lidCreatesHiddenContact() {
        ObjectNode p = message("123456789012345@lid", false, 1_700_000_000L);
        p.put("notifyName", "Hidden Hari");
        publish("message", p);

        Contact c = contacts.findByWorkspaceIdAndWaId(WS, "123456789012345@lid").orElseThrow();
        assertThat(c.getPhone()).isNull();
        assertThat(c.getName()).isEqualTo("Hidden Hari");
        assertThat(c.getSource()).isEqualTo(ContactSource.CHAT);
    }

    @Test
    void lidWithSenderPnFillsPhone() {
        ObjectNode p = message("123456789012345@lid", false, 1_700_000_000L);
        p.put("senderPn", "919812345678@c.us");
        publish("message", p);
        Contact c = contacts.findByWorkspaceIdAndWaId(WS, "123456789012345@lid").orElseThrow();
        assertThat(c.getPhone()).isEqualTo("919812345678");

        ObjectNode q = message("222222222222222@lid", false, 1_700_000_000L);
        q.putObject("_data").put("senderPn", "919812345600@c.us");
        publish("message", q);
        assertThat(contacts.findByWorkspaceIdAndWaId(WS, "222222222222222@lid").orElseThrow().getPhone())
                .isEqualTo("919812345600");

        ObjectNode r = message("333333333333333@lid", false, 1_700_000_000L);
        r.put("author", "919812345611@c.us");
        publish("message", r);
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

        ObjectNode p = message("123456789012345@lid", false, 1_700_000_000L);
        p.put("senderPn", "919812345678@c.us");
        publish("message", p);

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

        publish("message", message("919812345678@c.us", false, 1_700_000_000L));

        assertThat(contacts.count()).isEqualTo(1);
        Contact c = contacts.findById(imported.getId()).orElseThrow();
        assertThat(c.getWaId()).isEqualTo("919812345678@c.us");
        assertThat(c.getSource()).isEqualTo(ContactSource.IMPORT);
        assertThat(c.getLastMessageAt()).isEqualTo(Instant.ofEpochSecond(1_700_000_000L));
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

        ObjectNode p = message("919812345678@c.us", false, 1_700_000_000L);
        p.put("notifyName", "Push Name");
        publish("message", p);
        assertThat(contacts.findByWorkspaceIdAndPhone(WS, "919812345678").orElseThrow().getName())
                .isEqualTo("Typed Name");

        // a name that was never edited follows the sender's display name
        ObjectNode q = message("919812345600@c.us", false, 1_700_000_000L);
        q.put("notifyName", "First");
        publish("message", q);
        ObjectNode r = message("919812345600@c.us", false, 1_700_000_100L);
        r.put("notifyName", "Second");
        publish("message", r);
        assertThat(contacts.findByWorkspaceIdAndPhone(WS, "919812345600").orElseThrow().getName()).isEqualTo("Second");
    }

    @Test
    void replayIsIdempotent() {
        ObjectNode p = message("919812345678@c.us", false, 1_700_000_000L);
        p.put("notifyName", "Asha");
        publish("message", p);
        Contact first = contacts.findByWorkspaceIdAndPhone(WS, "919812345678").orElseThrow();
        publish("message", p);
        publish("message_create", p);

        assertThat(contacts.count()).isEqualTo(1);
        Contact again = contacts.findByWorkspaceIdAndPhone(WS, "919812345678").orElseThrow();
        assertThat(again.getVersion()).isEqualTo(first.getVersion());
    }

    @Test
    void sameChatInTwoBrandsMakesTwoContacts() {
        ObjectNode p = message("919812345678@c.us", false, 1_700_000_000L);
        publish("message", p);
        publisher.publishEvent(new WhatsAppEvent(this, "pn-2", "ws-2", "cust-2", "message", p, null, null));
        assertThat(contacts.count()).isEqualTo(2);
    }

    @Test
    void lastMessageAtNeverGoesBack() {
        publish("message", message("919812345678@c.us", false, 1_700_000_500L));
        publish("message", message("919812345678@c.us", false, 1_700_000_100L));
        assertThat(contacts.findByWorkspaceIdAndPhone(WS, "919812345678").orElseThrow().getLastMessageAt())
                .isEqualTo(Instant.ofEpochSecond(1_700_000_500L));
        publish("message", message("919812345678@c.us", false, 1_700_000_900L));
        assertThat(contacts.findByWorkspaceIdAndPhone(WS, "919812345678").orElseThrow().getLastMessageAt())
                .isEqualTo(Instant.ofEpochSecond(1_700_000_900L));
    }

    @Test
    void listenerSwallowsErrors() {
        // a null payload never reaches the bus (LoggingEventListener would fail on it), so call the listener directly
        ChatContactListener real = new ChatContactListener(new ContactService(contacts, java.time.Clock.systemUTC()));
        assertThatCode(() -> real.on(new WhatsAppEvent(this, "pn-1", WS, CUSTOMER, "message", null, null, null)))
                .doesNotThrowAnyException();
        assertThatCode(() -> publish("message", mapper.createObjectNode())).doesNotThrowAnyException();
        assertThatCode(() -> publish("message", mapper.createArrayNode())).doesNotThrowAnyException();
        assertThatCode(() -> publish("message", message("not-a-number@c.us", false, 1L))).doesNotThrowAnyException();
        assertThat(contacts.count()).isZero();

        ContactService failing = mock(ContactService.class);
        doThrow(new IllegalStateException("db down")).when(failing)
                .upsertFromChat(any(), any(), any(), any(), any(), any());
        ChatContactListener listener = new ChatContactListener(failing);
        WhatsAppEvent event = new WhatsAppEvent(this, "pn-1", WS, CUSTOMER, "message",
                message("919812345678@c.us", false, 1_700_000_000L), null, null);
        assertThatCode(() -> listener.on(event)).doesNotThrowAnyException();
    }
}
