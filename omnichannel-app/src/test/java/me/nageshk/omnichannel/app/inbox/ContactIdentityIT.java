package me.nageshk.omnichannel.app.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import me.nageshk.omnichannel.app.support.TestUsers;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactRepository;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatAction;
import me.nageshk.omnichannel.db.inbox.WaWebChatRepository;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.service.events.WhatsAppEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** One person under two WhatsApp ids is one contact. */
@SpringBootTest
class ContactIdentityIT {

    private static final String WS = "ws-1";
    private static final String CUSTOMER = "cust-1";
    private static final long T = 1_700_000_000L;
    private static final String PHONE = "919812345678";
    private static final String LID = "123456789012345@lid";

    @Autowired ApplicationEventPublisher publisher;
    @Autowired ContactRepository contacts;
    @Autowired WaWebChatRepository chats;
    @Autowired MongoTemplate mongo;
    @Autowired TestUsers testUsers;
    @Autowired ObjectMapper mapper;

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
        mongo.remove(new Query(), Contact.class);
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

    private void create(JsonNode payload) {
        publisher.publishEvent(new WhatsAppEvent(this, "pn-1", WS, CUSTOMER, "message_create", payload, null, null));
    }

    private Contact contact(String phone, String... waIds) {
        Contact c = new Contact();
        c.setWorkspaceId(WS);
        c.setCustomerId(CUSTOMER);
        c.setPhone(phone);
        c.setWaIds(new ArrayList<>(List.of(waIds)));
        return contacts.save(c);
    }

    private WaWebChat onlyChat() {
        assertThat(chats.count()).isEqualTo(1);
        return chats.findAll().getFirst();
    }

    @Test
    void lidWithPhoneJoinsTheExistingContact() {
        Contact known = contact(PHONE, PHONE + "@c.us");
        ObjectNode p = message(LID, false, T);
        p.put("senderPn", PHONE + "@c.us");
        create(p);

        assertThat(contacts.count()).isEqualTo(1);
        Contact c = contacts.findById(known.getId()).orElseThrow();
        assertThat(c.getWaIds()).containsExactly(PHONE + "@c.us", LID);
        assertThat(c.getPhone()).isEqualTo(PHONE);
        assertThat(onlyChat().getContactId()).isEqualTo(known.getId());
    }

    @Test
    void lidWithoutPhoneCreatesAHiddenContact() {
        contact(PHONE, PHONE + "@c.us");
        create(message(LID, false, T));

        assertThat(contacts.count()).isEqualTo(2);
        Contact c = contacts.findByWorkspaceIdAndWaIdsContaining(WS, LID).orElseThrow();
        assertThat(c.getWaIds()).containsExactly(LID);
        assertThat(c.getPhone()).isNull();
        assertThat(onlyChat().getContactId()).isEqualTo(c.getId());
    }

    @Test
    void secondMessageUnderTheLidFindsTheJoinedContact() {
        Contact known = contact(PHONE, PHONE + "@c.us");
        ObjectNode p = message(LID, false, T);
        p.put("senderPn", PHONE + "@c.us");
        create(p);
        create(message(LID, false, T + 60));

        assertThat(contacts.count()).isEqualTo(1);
        assertThat(contacts.findById(known.getId()).orElseThrow().getWaIds()).containsExactly(PHONE + "@c.us", LID);
        assertThat(onlyChat().getContactId()).isEqualTo(known.getId());
    }

    @Test
    void phoneMessageFindsTheContactByWaIds() {
        Contact known = contact(null, LID, PHONE + "@c.us");
        create(message(PHONE + "@c.us", false, T));

        assertThat(contacts.count()).isEqualTo(1);
        assertThat(contacts.findById(known.getId()).orElseThrow().getWaIds()).containsExactly(LID, PHONE + "@c.us");
        assertThat(onlyChat().getContactId()).isEqualTo(known.getId());
    }

    @Test
    void phoneMessageRecordsItsIdOnThePhoneContact() {
        Contact known = contact(PHONE, LID);
        create(message(PHONE + "@c.us", false, T));

        assertThat(contacts.count()).isEqualTo(1);
        assertThat(contacts.findById(known.getId()).orElseThrow().getWaIds()).containsExactly(LID, PHONE + "@c.us");
        assertThat(onlyChat().getContactId()).isEqualTo(known.getId());
    }
}
