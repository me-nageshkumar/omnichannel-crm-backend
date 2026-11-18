package me.nageshk.omnichannel.app.phone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.app.support.FakeDevice;
import me.nageshk.omnichannel.app.support.TestBilling;
import me.nageshk.omnichannel.app.support.TestDevices;
import me.nageshk.omnichannel.app.support.TestUsers;
import me.nageshk.omnichannel.common.billing.LimitKey;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactRepository;
import me.nageshk.omnichannel.db.contact.ContactSource;
import me.nageshk.omnichannel.db.inbox.ChatStatus;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatRepository;
import me.nageshk.omnichannel.db.phone.PhoneNumber;
import me.nageshk.omnichannel.db.phone.PhoneNumberRepository;
import me.nageshk.omnichannel.db.tenant.Customer;
import me.nageshk.omnichannel.db.tenant.Workspace;
import me.nageshk.omnichannel.security.access.BuiltInRoles;
import me.nageshk.omnichannel.security.phone.DeviceKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.socket.CloseStatus;
import tools.jackson.databind.JsonNode;

/** A number that moved to another brand leaves an archive behind; a number that is moving cannot connect. */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ArchivedNumberIT {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired TestUsers testUsers;
    @Autowired TestDevices testDevices;
    @Autowired TestBilling billing;
    @Autowired PhoneNumberRepository numbers;
    @Autowired WaWebChatRepository chats;
    @Autowired ContactRepository contacts;
    @Autowired DeviceKeys keys;

    Customer acme;
    Workspace brand;
    String token;

    @BeforeEach
    void setUp() {
        testUsers.deleteAll();
        numbers.deleteAll();
        billing.reset();
        acme = testUsers.customer("Acme");
        brand = testUsers.workspace(acme.getId(), "Brand A");
        token = testUsers.accessToken(
                testUsers.createInCustomer(acme.getId(), "boss", BuiltInRoles.CUSTOMER_ADMIN, List.of()));
    }

    @AfterEach
    void clean() {
        chats.deleteAll();
        numbers.deleteAll();
        testUsers.deleteAll();
    }

    private PhoneNumber archive(String archiveOf) {
        PhoneNumber a = new PhoneNumber();
        a.setCustomerId(acme.getId());
        a.setWorkspaceId(brand.getId());
        a.setArchiveOf(archiveOf);
        a.setArchivedNumber(archiveOf);
        a.setArchivedAt(Instant.parse("2026-11-18T10:00:00Z"));
        return numbers.save(a);
    }

    private ResponseEntity<JsonNode> call(HttpMethod method, String path, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, method, new HttpEntity<>(body, h), JsonNode.class);
    }

    private String numbersPath() {
        return "/api/workspaces/" + brand.getId() + "/phone-numbers";
    }

    @Test
    void archiveDoesNotCountTowardLimit() {
        billing.assign(acme.getId(), Set.of(), Map.of(LimitKey.PHONE_NUMBERS, 1));
        archive("919800000009");

        assertThat(call(HttpMethod.POST, numbersPath(), Map.of("number", "919800000001")).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> second = call(HttpMethod.POST, numbersPath(), Map.of("number", "919800000002"));
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody().get("key").asString()).isEqualTo("PHONE_NUMBERS");
    }

    @Test
    void twoArchivesWithoutNumberAreAllowed() {
        assertThatCode(() -> {
            archive("919800000001");
            archive("919800000002");
        }).doesNotThrowAnyException();
        // while a duplicate real number is still refused
        testDevices.number(brand, "919800000003");
        assertThatCode(() -> testDevices.number(brand, "919800000003")).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void archiveViewSaysArchived() {
        PhoneNumber a = archive("919800000001");

        ResponseEntity<JsonNode> res = call(HttpMethod.GET, numbersPath(), null);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode view = res.getBody().get(0);
        assertThat(view.get("id").asString()).isEqualTo(a.getId());
        assertThat(view.get("status").asString()).isEqualTo("ARCHIVED");
        assertThat(view.get("number").asString()).isEqualTo("919800000001");
        assertThat(view.get("archiveOf").asString()).isEqualTo("919800000001");
        assertThat(view.get("archivedAt").isNull()).isFalse();
    }

    @Test
    void sendOnArchiveRefused() {
        PhoneNumber a = archive("919800000001");
        Contact c = new Contact();
        c.setWorkspaceId(brand.getId());
        c.setCustomerId(acme.getId());
        c.setName("Aarav");
        c.setPhone("919811111111");
        c.setSource(ContactSource.CHAT);
        c = contacts.save(c);
        WaWebChat chat = new WaWebChat();
        chat.setCustomerId(acme.getId());
        chat.setWorkspaceId(brand.getId());
        chat.setPhoneNumberId(a.getId());
        chat.setRemoteId("919811111111@c.us");
        chat.setContactId(c.getId());
        chat.setStatus(ChatStatus.OPEN);
        chat = chats.save(chat);

        ResponseEntity<JsonNode> res = call(HttpMethod.POST,
                "/api/workspaces/" + brand.getId() + "/inbox/chats/" + chat.getId() + "/messages",
                Map.of("text", "hello"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().get("code").asString()).isEqualTo("NUMBER_ARCHIVED");
        assertThat(res.getBody().get("message").asString()).isEqualTo("This number moved to another brand on 18 Nov.");
    }

    @Test
    void archiveRefusesEnableConnectCodeAndTeam() {
        PhoneNumber a = archive("919800000001");
        for (String action : List.of("/enable", "/connect-code", "/revoke-device")) {
            ResponseEntity<JsonNode> res = call(HttpMethod.POST, numbersPath() + "/" + a.getId() + action, null);
            assertThat(res.getStatusCode()).as(action).isEqualTo(HttpStatus.CONFLICT);
            assertThat(res.getBody().get("code").asString()).as(action).isEqualTo("number_archived");
        }
        ResponseEntity<JsonNode> team = call(HttpMethod.PUT, numbersPath() + "/" + a.getId() + "/team",
                Map.of("teamId", "x"));
        assertThat(team.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(team.getBody().get("code").asString()).isEqualTo("number_archived");
    }

    @Test
    void archiveCanBeRelabelledAndDeleted() {
        PhoneNumber a = archive("919800000001");
        assertThat(call(HttpMethod.PUT, numbersPath() + "/" + a.getId(), Map.of("label", "old")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.DELETE, numbersPath() + "/" + a.getId(), null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void archiveAndMovingKeysAreNotUsable() {
        DeviceKeys.Connected connected = testDevices.connected("919800000005");
        PhoneNumber n = numbers.findById(connected.phoneNumberId()).orElseThrow();
        assertThat(keys.authenticate(connected.deviceKey())).isPresent();
        assertThat(keys.isMovingKey(connected.deviceKey())).isFalse();

        n.setMovingSince(Instant.now());
        numbers.save(n);

        assertThat(keys.authenticate(connected.deviceKey())).isEmpty();
        assertThat(keys.isMovingKey(connected.deviceKey())).isTrue();
    }

    @Test
    void movingKeyClosesWith4503() throws Exception {
        DeviceKeys.Connected connected = testDevices.connected("919800000006");
        PhoneNumber n = numbers.findById(connected.phoneNumberId()).orElseThrow();
        n.setMovingSince(Instant.now());
        numbers.save(n);

        FakeDevice device = FakeDevice.connect(port);
        device.send(me.nageshk.omnichannel.service.rpc.Envelope.auth(connected.deviceKey()));
        CloseStatus status = device.awaitClose();

        assertThat(status.getCode()).isEqualTo(4503);
        assertThat(status.getReason()).isEqualTo("number is moving");
    }
}
