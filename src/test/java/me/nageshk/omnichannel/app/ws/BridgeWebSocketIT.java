package me.nageshk.omnichannel.app.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import me.nageshk.omnichannel.app.support.FakeDevice;
import me.nageshk.omnichannel.app.support.TestDevices;
import me.nageshk.omnichannel.app.support.TestUsers;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.db.inbox.WaWebMessageRepository;
import me.nageshk.omnichannel.db.phone.PhoneNumber;
import me.nageshk.omnichannel.db.phone.PhoneNumberRepository;
import me.nageshk.omnichannel.db.tenant.Customer;
import me.nageshk.omnichannel.db.tenant.Workspace;
import me.nageshk.omnichannel.db.user.WorkspaceRole;
import me.nageshk.omnichannel.security.access.BuiltInRoles;
import me.nageshk.omnichannel.security.phone.DeviceKeys;
import me.nageshk.omnichannel.service.contact.ContactService;
import me.nageshk.omnichannel.service.events.WhatsAppEvent;
import me.nageshk.omnichannel.service.phone.LiveDevices;
import me.nageshk.omnichannel.service.rpc.Envelope;
import me.nageshk.omnichannel.service.ws.DeviceRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.socket.CloseStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "omnichannel.rpc.auth-timeout=1s")
class BridgeWebSocketIT {

    static final String NUMBER = "919928088743";

    /** Nested @TestConfiguration classes are registered as beans automatically; no @Bean needed. */
    @TestConfiguration
    static class Capture {
        final BlockingQueue<WhatsAppEvent> events = new LinkedBlockingQueue<>();

        @EventListener
        void on(WhatsAppEvent e) {
            events.add(e);
        }
    }

    @MockitoSpyBean ContactService contactService;
    @Autowired WaWebMessageRepository messageRepository;
    @Autowired MongoTemplate mongo;
    @LocalServerPort int port;
    @Autowired TestUsers testUsers;
    @Autowired TestDevices testDevices;
    @Autowired PhoneNumberRepository numbers;
    @Autowired DeviceRegistry registry;
    @Autowired LiveDevices live;
    @Autowired Capture capture;
    @Autowired ObjectMapper mapper;
    @Autowired TestRestTemplate rest;

    Customer acme;
    Workspace brandA;
    PhoneNumber number;
    String key;
    String wmToken;
    String bossToken;

    @BeforeEach
    void setUp() {
        testUsers.deleteAll();
        acme = testUsers.customer("Acme");
        brandA = testUsers.workspace(acme.getId(), "Brand A");
        number = testDevices.number(brandA, NUMBER);
        key = testDevices.redeem(number).deviceKey();
        bossToken = testUsers.accessToken(
                testUsers.createInCustomer(acme.getId(), "boss", BuiltInRoles.CUSTOMER_ADMIN, List.of()));
        wmToken = testUsers.accessToken(testUsers.createInCustomer(acme.getId(), "wm", null,
                List.of(new WorkspaceRole(brandA.getId(), testUsers.roleId(BuiltInRoles.WORKSPACE_MANAGER)))));
        capture.events.clear();
    }

    @AfterEach
    void clean() {
        testUsers.deleteAll();
    }

    private ObjectNode info(String wid) {
        return mapper.createObjectNode().put("pushname", "N").put("wid", wid);
    }

    /** Sends hello with the given WhatsApp id and returns the reply (or null if the socket closed instead). */
    private Envelope hello(FakeDevice device, String wid) throws Exception {
        ObjectNode params = mapper.createObjectNode().put("waState", "CONNECTED");
        params.set("info", info(wid));
        device.send(Envelope.request("h1", "hello", params));
        return device.next();
    }

    private FakeDevice confirmed() throws Exception {
        FakeDevice device = FakeDevice.connectAndAuth(port, key);
        hello(device, NUMBER + "@c.us");
        return device;
    }

    private void postAs(String token, String path) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        var res = rest.exchange(path, HttpMethod.POST, new HttpEntity<>(h), JsonNode.class);
        assertThat(res.getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
    }

    private String numberPath(String action) {
        return "/api/workspaces/" + brandA.getId() + "/phone-numbers/" + number.getId() + "/" + action;
    }

    @Test
    void deviceKeyAuthenticates() throws Exception {
        FakeDevice device = FakeDevice.connect(port);
        device.send(Envelope.auth(key));
        Envelope reply = device.next();
        assertThat(reply.id()).isEqualTo("auth");
        assertThat(reply.result().get("ok").asBoolean()).isTrue();
        assertThat(reply.result().get("phoneNumberId").asString()).isEqualTo(number.getId());
        assertThat(reply.result().get("number").asString()).isEqualTo(NUMBER);
        assertThat(reply.result().get("brandName").asString()).isEqualTo("Brand A");
        assertThat(registry.find(number.getId())).isPresent();
        assertThat(live.stateOf(number.getId())).contains(LiveDevices.LiveState.WAITING_FOR_LOGIN);
        device.close();
    }

    @Test
    void closesWith4401WhenNoAuthFrameArrivesInTime() throws Exception {
        FakeDevice device = FakeDevice.connect(port);
        assertThat(device.awaitClose().getCode()).isEqualTo(4401);
    }

    @Test
    void invalidKeyCloses4401() throws Exception {
        FakeDevice device = FakeDevice.connect(port);
        device.send(Envelope.auth("dk_garbage"));
        assertThat(device.awaitClose().getCode()).isEqualTo(4401);
    }

    @Test
    void userTokenIsNotADeviceKey() throws Exception {
        FakeDevice device = FakeDevice.connect(port);
        device.send(Envelope.auth(bossToken));
        assertThat(device.awaitClose().getCode()).isEqualTo(4401);
    }

    @Test
    void disabledNumberKeyCloses4403() throws Exception {
        postAs(wmToken, numberPath("disable"));
        FakeDevice device = FakeDevice.connect(port);
        device.send(Envelope.auth(key));
        CloseStatus status = device.awaitClose();
        assertThat(status.getCode()).isEqualTo(4403);
        assertThat(status.getReason()).isEqualTo("This number, its brand or its company is disabled.");
    }

    @Test
    void disabledBrandKeyCloses4403AndWorksAfterEnable() throws Exception {
        postAs(bossToken, "/api/workspaces/" + brandA.getId() + "/disable");
        FakeDevice refused = FakeDevice.connect(port);
        refused.send(Envelope.auth(key));
        assertThat(refused.awaitClose().getCode()).isEqualTo(4403);
        postAs(bossToken, "/api/workspaces/" + brandA.getId() + "/enable");
        try (FakeDevice device = FakeDevice.connectAndAuth(port, key)) {
            assertThat(device.isOpen()).isTrue();
        }
    }

    @Test
    void newKeyStartsAFreshEventSequence() throws Exception {
        try (FakeDevice device = confirmed()) {
            device.send(Envelope.durableEvent("message", mapper.createObjectNode().put("body", "old"), 5, 1L));
            assertThat(device.next().seq()).isEqualTo(5L);
            assertThat(capture.events.poll(5, TimeUnit.SECONDS)).isNotNull();
        }
        String again = testDevices.redeem(numbers.findById(number.getId()).orElseThrow()).deviceKey();
        try (FakeDevice device = FakeDevice.connectAndAuth(port, again)) {
            hello(device, NUMBER + "@c.us");
            device.send(Envelope.durableEvent("message", mapper.createObjectNode().put("body", "new"), 1, 1L));
            assertThat(device.next().seq()).isEqualTo(1L);
            WhatsAppEvent e = capture.events.poll(5, TimeUnit.SECONDS);
            assertThat(e).as("seq 1 of a new device is not a duplicate").isNotNull();
            assertThat(e.payload().get("body").asString()).isEqualTo("new");
        }
    }

    @Test
    void matchingHelloConfirms() throws Exception {
        try (FakeDevice device = FakeDevice.connectAndAuth(port, key)) {
            Envelope reply = hello(device, NUMBER + "@c.us");
            assertThat(reply.result().get("ok").asBoolean()).isTrue();
            assertThat(reply.result().get("scripts").get("page").get("sha256").asString()).hasSize(64);
            assertThat(registry.find(number.getId()).orElseThrow().confirmed()).isTrue();
            assertThat(live.stateOf(number.getId())).contains(LiveDevices.LiveState.CONNECTED);
            assertThat(numbers.findById(number.getId()).orElseThrow().getLastConnectedAt()).isNotNull();
        }
    }

    @Test
    void wrongNumberCloses4412AndIsRecorded() throws Exception {
        FakeDevice device = FakeDevice.connectAndAuth(port, key);
        ObjectNode params = mapper.createObjectNode();
        params.set("info", info("911111111111@c.us"));
        device.send(Envelope.request("h1", "hello", params));
        CloseStatus status = device.awaitClose();
        assertThat(status.getCode()).isEqualTo(4412);
        assertThat(status.getReason()).contains("+911111111111").contains("+" + NUMBER);
        PhoneNumber stored = numbers.findById(number.getId()).orElseThrow();
        assertThat(stored.getLastWrongNumber()).isEqualTo("911111111111");
        assertThat(stored.getLastWrongNumberAt()).isNotNull();
    }

    @Test
    void wrongNumberInReadyEventCloses4412() throws Exception {
        FakeDevice device = FakeDevice.connectAndAuth(port, key);
        device.send(Envelope.event("ready", info("911111111111@c.us")));
        assertThat(device.awaitClose().getCode()).isEqualTo(4412);
        assertThat(capture.events.poll(300, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void lidOnlyNeverConfirms() throws Exception {
        try (FakeDevice device = FakeDevice.connectAndAuth(port, key)) {
            hello(device, "123456789@lid");
            assertThat(device.isOpen()).isTrue();
            assertThat(registry.find(number.getId()).orElseThrow().confirmed()).isFalse();
            device.send(Envelope.event("message", mapper.createObjectNode().put("body", "hi")));
            assertThat(capture.events.poll(300, TimeUnit.MILLISECONDS)).isNull();

            ObjectNode withPn = info("123456789@lid").put("pnWid", NUMBER + "@c.us");
            device.send(Envelope.event("ready", withPn));
            WhatsAppEvent ready = capture.events.poll(5, TimeUnit.SECONDS);
            assertThat(ready).isNotNull();
            assertThat(registry.find(number.getId()).orElseThrow().confirmed()).isTrue();
        }
    }

    @Test
    void unconfirmedEventsAreNotAcked() throws Exception {
        try (FakeDevice device = FakeDevice.connectAndAuth(port, key)) {
            var payload = mapper.createObjectNode().put("body", "offline");
            device.send(Envelope.durableEvent("message", payload, 1, 1700000000000L));
            device.send(Envelope.ping());
            assertThat(device.next().type()).isEqualTo(Envelope.PONG);
            assertThat(capture.events.poll(300, TimeUnit.MILLISECONDS)).isNull();

            hello(device, NUMBER + "@c.us");
            device.send(Envelope.durableEvent("message", payload, 1, 1700000000000L));
            Envelope ack = device.next();
            assertThat(ack.type()).isEqualTo(Envelope.EVENT_ACK);
            assertThat(ack.seq()).isEqualTo(1L);
            assertThat(capture.events.poll(5, TimeUnit.SECONDS).payload().get("body").asString()).isEqualTo("offline");
        }
    }

    @Test
    void foreignAcctIsDropped() throws Exception {
        try (FakeDevice device = confirmed()) {
            capture.events.clear();
            device.send(Envelope.durableEvent("message", mapper.createObjectNode().put("body", "x"), 5, 1L, "911111111111"));
            Envelope ack = device.next();
            assertThat(ack.type()).isEqualTo(Envelope.EVENT_ACK);
            assertThat(ack.seq()).isEqualTo(5L);
            assertThat(capture.events.poll(300, TimeUnit.MILLISECONDS)).isNull();

            device.send(Envelope.durableEvent("message", mapper.createObjectNode().put("body", "y"), 6, 1L, NUMBER));
            assertThat(device.next().seq()).isEqualTo(6L);
            assertThat(capture.events.poll(5, TimeUnit.SECONDS).payload().get("body").asString()).isEqualTo("y");
        }
    }

    @Test
    void eventsArePublishedWithTheirBrand() throws Exception {
        try (FakeDevice device = confirmed()) {
            device.send(Envelope.event("ready", info(NUMBER + "@c.us")));
            WhatsAppEvent ready = capture.events.poll(5, TimeUnit.SECONDS);
            assertThat(ready.phoneNumberId()).isEqualTo(number.getId());
            assertThat(ready.workspaceId()).isEqualTo(brandA.getId());
            assertThat(ready.customerId()).isEqualTo(acme.getId());
            assertThat(registry.find(number.getId()).orElseThrow().waState()).isEqualTo("CONNECTED");

            device.send(Envelope.event("change_state", mapper.createObjectNode().put("state", "TIMEOUT")));
            capture.events.poll(5, TimeUnit.SECONDS);
            assertThat(registry.find(number.getId()).orElseThrow().waState()).isEqualTo("TIMEOUT");
        }
    }

    @Test
    void durableEventsAreAckedAndDuplicatesAreNotRepublished() throws Exception {
        try (FakeDevice device = confirmed()) {
            var payload = mapper.createObjectNode().put("body", "offline msg");
            device.send(Envelope.durableEvent("message", payload, 1, 1700000000000L));
            assertThat(device.next().seq()).isEqualTo(1L);
            WhatsAppEvent published = capture.events.poll(5, TimeUnit.SECONDS);
            assertThat(published.seq()).isEqualTo(1L);
            assertThat(published.ts()).isEqualTo(1700000000000L);

            device.send(Envelope.durableEvent("message", payload, 1, 1700000000000L));
            assertThat(device.next().seq()).isEqualTo(1L);
            assertThat(capture.events.poll(500, TimeUnit.MILLISECONDS)).isNull();
        }
    }

    private ObjectNode inbound(String id, long epochSeconds) {
        ObjectNode p = mapper.createObjectNode();
        p.putObject("id").put("fromMe", false).put("remote", "919911111111@c.us").put("id", id);
        p.put("fromMe", false).put("timestamp", epochSeconds).put("body", "hello");
        return p;
    }

    private void clearInbox() {
        mongo.remove(new Query(), WaWebChat.class);
        mongo.remove(new Query(), WaWebMessage.class);
    }

    @Test
    void eventThatCouldNotBeStoredIsNotAckedAndIsStoredOnceWhenTheOutboxReplaysIt() throws Exception {
        clearInbox();
        doThrow(new DataAccessResourceFailureException("mongo blip")).doCallRealMethod().when(contactService)
                .upsertFromChat(any(), any(), any(), any(), any(), any());
        ObjectNode payload = inbound("ABC1", 1_700_000_000L);

        try (FakeDevice device = confirmed()) {
            device.send(Envelope.durableEvent("message_create", payload, 1, 1_700_000_000_000L));
            // no ack: the extension keeps the event; the socket is closed so it reconnects and replays in order
            assertThat(device.awaitClose().getCode()).isEqualTo(CloseStatus.SERVER_ERROR.getCode());
        }
        assertThat(messageRepository.count()).isZero();

        try (FakeDevice device = confirmed()) {
            device.send(Envelope.durableEvent("message_create", payload, 1, 1_700_000_000_000L));
            Envelope ack = device.next();
            assertThat(ack.type()).isEqualTo(Envelope.EVENT_ACK);
            assertThat(ack.seq()).isEqualTo(1L);
            device.send(Envelope.durableEvent("message_create", payload, 1, 1_700_000_000_000L));
            assertThat(device.next().seq()).isEqualTo(1L);
        }
        assertThat(messageRepository.count()).isEqualTo(1);
        clearInbox();
    }

    @Test
    void eventsSentAfterAFailedOneAreNotAckedEitherBecauseAcksAreCumulative() throws Exception {
        clearInbox();
        doThrow(new DataAccessResourceFailureException("mongo blip")).doCallRealMethod().when(contactService)
                .upsertFromChat(any(), any(), any(), any(), any(), any());

        try (FakeDevice device = confirmed()) {
            device.send(Envelope.durableEvent("message_create", inbound("ABC1", 1_700_000_000L), 1, 1L));
            device.send(Envelope.durableEvent("message_create", inbound("ABC2", 1_700_000_001L), 2, 2L));
            assertThat(device.awaitClose().getCode()).isEqualTo(1011);
            assertThat(device.poll(java.time.Duration.ofMillis(200))).isEmpty();
        }
        assertThat(messageRepository.count()).isZero();
    }

    @Test
    void eventThatKeepsFailingIsAckedAfterThreeAttemptsSoItCannotLoopForever() throws Exception {
        clearInbox();
        doThrow(new DataAccessResourceFailureException("mongo down")).when(contactService)
                .upsertFromChat(any(), any(), any(), any(), any(), any());
        ObjectNode payload = inbound("ABC2", 1_700_000_000L);

        for (int attempt = 1; attempt <= 2; attempt++) {
            try (FakeDevice device = confirmed()) {
                device.send(Envelope.durableEvent("message_create", payload, 4, 1_700_000_000_000L));
                assertThat(device.awaitClose().getCode()).as("attempt %d", attempt).isEqualTo(1011);
            }
        }
        try (FakeDevice device = confirmed()) {
            device.send(Envelope.durableEvent("message_create", payload, 4, 1_700_000_000_000L));
            Envelope ack = device.next();
            assertThat(ack.type()).isEqualTo(Envelope.EVENT_ACK);
            assertThat(ack.seq()).isEqualTo(4L);
        }
        assertThat(messageRepository.count()).isZero();
    }

    @Test
    void pingGetsPong() throws Exception {
        try (FakeDevice device = FakeDevice.connectAndAuth(port, key)) {
            device.send(Envelope.ping());
            assertThat(device.next().type()).isEqualTo(Envelope.PONG);
        }
    }

    @Test
    void unknownMethodIsRejected() throws Exception {
        try (FakeDevice device = confirmed()) {
            device.send(Envelope.request("h2", "nope", null));
            assertThat(device.next().error().code()).isEqualTo("METHOD_NOT_FOUND");
        }
    }

    @Test
    void disablingNumberClosesSocket() throws Exception {
        FakeDevice device = confirmed();
        postAs(wmToken, numberPath("disable"));
        CloseStatus status = device.awaitClose();
        assertThat(status.getCode()).isEqualTo(4403);
        assertThat(status.getReason()).isEqualTo("This number, its brand or its company is disabled.");
    }

    @Test
    void disablingBrandClosesSocket() throws Exception {
        FakeDevice device = confirmed();
        postAs(bossToken, "/api/workspaces/" + brandA.getId() + "/disable");
        assertThat(device.awaitClose().getCode()).isEqualTo(4403);
    }

    @Test
    void disablingCustomerClosesSocket() throws Exception {
        FakeDevice device = confirmed();
        String amToken = testUsers.accessToken(testUsers.create("am", "pw", BuiltInRoles.ACCOUNT_MANAGER, null));
        postAs(amToken, "/api/customers/" + acme.getId() + "/disable");
        assertThat(device.awaitClose().getCode()).isEqualTo(4403);
    }

    @Test
    void otherBrandsDisableLeavesSocketOpen() throws Exception {
        Workspace brandB = testUsers.workspace(acme.getId(), "Brand B");
        try (FakeDevice device = confirmed()) {
            postAs(bossToken, "/api/workspaces/" + brandB.getId() + "/disable");
            device.send(Envelope.ping());
            assertThat(device.next().type()).isEqualTo(Envelope.PONG);
        }
    }

    @Test
    void revokeCloses4410() throws Exception {
        FakeDevice device = confirmed();
        postAs(wmToken, numberPath("revoke-device"));
        assertThat(device.awaitClose().getCode()).isEqualTo(4410);
    }

    @Test
    void newKeyReplaces4409() throws Exception {
        FakeDevice device = confirmed();
        DeviceKeys.Connected again = testDevices.redeem(numbers.findById(number.getId()).orElseThrow());
        assertThat(device.awaitClose().getCode()).isEqualTo(4409);
        try (FakeDevice second = FakeDevice.connectAndAuth(port, again.deviceKey())) {
            assertThat(second.isOpen()).isTrue();
        }
    }

    @Test
    void disconnectUnregistersDevice() throws Exception {
        FakeDevice device = confirmed();
        device.close();
        Thread.sleep(200); // afterConnectionClosed runs on the container thread right after the handshake
        assertThat(registry.find(number.getId())).isEmpty();
        assertThat(numbers.findById(number.getId()).orElseThrow().getLastSeenAt()).isNotNull();
    }
}
