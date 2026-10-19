package me.nageshk.omnichannel.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.nageshk.omnichannel.auth.JwtService;
import me.nageshk.omnichannel.support.TestUsers;
import me.nageshk.omnichannel.events.WhatsAppEvent;
import me.nageshk.omnichannel.rpc.Envelope;
import me.nageshk.omnichannel.support.FakeDevice;
import me.nageshk.omnichannel.user.User;
import me.nageshk.omnichannel.user.UserRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.event.EventListener;
import org.springframework.web.socket.CloseStatus;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "omnichannel.rpc.auth-timeout=1s")
class BridgeWebSocketIT {

    /** Nested @TestConfiguration classes are registered as beans automatically; no @Bean needed. */
    @TestConfiguration
    static class Capture {
        final BlockingQueue<WhatsAppEvent> events = new LinkedBlockingQueue<>();

        @EventListener
        void on(WhatsAppEvent e) {
            events.add(e);
        }
    }

    @LocalServerPort int port;
    @Autowired JwtService jwt;
    @Autowired TestUsers testUsers;
    @Autowired UserRepository repo;
    String deviceToken;

    @BeforeEach
    void seedDeviceUser() {
        testUsers.deleteAll();
        deviceToken = testUsers.tokenFor("demo");
    }
    @Autowired DeviceRegistry registry;
    @Autowired Capture capture;
    @Autowired ObjectMapper mapper;

    @Test
    void authenticatesAndRegistersDevice() throws Exception {
        try (FakeDevice device = FakeDevice.connectAndAuth(port, deviceToken)) {
            assertThat(registry.find("demo")).isPresent();
            assertThat(registry.find("demo").get().waState()).isEqualTo(DeviceSession.STATE_UNKNOWN);
        }
    }

    @Test
    void closesWith4401WhenNoAuthFrameArrivesInTime() throws Exception {
        FakeDevice device = FakeDevice.connect(port);
        CloseStatus status = device.awaitClose();
        assertThat(status.getCode()).isEqualTo(4401);
    }

    @Test
    void closesWith4401OnInvalidToken() throws Exception {
        FakeDevice device = FakeDevice.connect(port);
        device.send(Envelope.auth("garbage"));
        assertThat(device.awaitClose().getCode()).isEqualTo(4401);
    }

    @Test
    void authWithStaleTokenVersionIsRefused() throws Exception {
        User u = testUsers.create("stale", "pw", "WHATSAPP_DEVICE", null);
        String token = testUsers.accessToken(u);
        u.setTokenVersion(1L);
        repo.save(u);

        FakeDevice device = FakeDevice.connect(port);
        device.send(Envelope.auth(token));
        assertThat(device.awaitClose().getCode()).isEqualTo(4401);
    }

    @Test
    void authWithRevokedTokenIsRefused() throws Exception {
        HttpResponse<Void> logout = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/logout"))
                        .header("Authorization", "Bearer " + deviceToken)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(logout.statusCode()).isEqualTo(204);

        FakeDevice device = FakeDevice.connect(port);
        device.send(Envelope.auth(deviceToken));
        assertThat(device.awaitClose().getCode()).isEqualTo(4401);
    }

    @Test
    void authWithPendingTokenIsRefused() throws Exception {
        FakeDevice device = FakeDevice.connect(port);
        device.send(Envelope.auth(jwt.issuePendingToken("demo")));
        assertThat(device.awaitClose().getCode()).isEqualTo(4401);
    }

    @Test
    void secondDeviceReplacesFirstWith4409() throws Exception {
        String token = deviceToken;
        FakeDevice first = FakeDevice.connectAndAuth(port, token);
        try (FakeDevice second = FakeDevice.connectAndAuth(port, token)) {
            assertThat(first.awaitClose().getCode()).isEqualTo(4409);
            assertThat(registry.find("demo")).isPresent();
            assertThat(second.isOpen()).isTrue();
        }
    }

    @Test
    void pingGetsPong() throws Exception {
        try (FakeDevice device = FakeDevice.connectAndAuth(port, deviceToken)) {
            device.send(Envelope.ping());
            assertThat(device.next().type()).isEqualTo(Envelope.PONG);
        }
    }

    @Test
    void helloUpdatesStateAndUnknownMethodIsRejected() throws Exception {
        try (FakeDevice device = FakeDevice.connectAndAuth(port, deviceToken)) {
            var params = mapper.createObjectNode().put("waState", "CONNECTED");
            params.set("info", mapper.createObjectNode().put("pushname", "Nagesh"));
            device.send(Envelope.request("h1", "hello", params));
            Envelope reply = device.next();
            assertThat(reply.id()).isEqualTo("h1");
            assertThat(reply.result().get("ok").asBoolean()).isTrue();
            assertThat(reply.result().get("scripts").get("page").get("sha256").asText()).hasSize(64);

            DeviceSession session = registry.find("demo").orElseThrow();
            assertThat(session.waState()).isEqualTo("CONNECTED");
            assertThat(session.info().get("pushname").asText()).isEqualTo("Nagesh");

            device.send(Envelope.request("h2", "nope", null));
            Envelope err = device.next();
            assertThat(err.error().code()).isEqualTo("METHOD_NOT_FOUND");
        }
    }

    @Test
    void eventsArePublishedAndStateTracked() throws Exception {
        try (FakeDevice device = FakeDevice.connectAndAuth(port, deviceToken)) {
            capture.events.clear();

            device.send(Envelope.event("ready", mapper.createObjectNode().put("pushname", "N").put("waVersion", "2.3000.1")));
            WhatsAppEvent ready = capture.events.poll(5, TimeUnit.SECONDS);
            assertThat(ready).isNotNull();
            assertThat(ready.username()).isEqualTo("demo");
            assertThat(ready.name()).isEqualTo("ready");
            assertThat(registry.find("demo").orElseThrow().waState()).isEqualTo("CONNECTED");

            device.send(Envelope.event("message", mapper.createObjectNode().put("body", "hi").put("from", "1@c.us")));
            WhatsAppEvent msg = capture.events.poll(5, TimeUnit.SECONDS);
            assertThat(msg.name()).isEqualTo("message");
            assertThat(msg.payload().get("body").asText()).isEqualTo("hi");

            device.send(Envelope.event("change_state", mapper.createObjectNode().put("state", "TIMEOUT")));
            capture.events.poll(5, TimeUnit.SECONDS);
            assertThat(registry.find("demo").orElseThrow().waState()).isEqualTo("TIMEOUT");
        }
    }

    @Test
    void durableEventsAreAckedAndDuplicatesAreNotRepublished() throws Exception {
        try (FakeDevice device = FakeDevice.connectAndAuth(port, deviceToken)) {
            capture.events.clear();
            var payload = mapper.createObjectNode().put("body", "offline msg");

            device.send(Envelope.durableEvent("message", payload, 1, 1700000000000L));
            Envelope ack = device.next();
            assertThat(ack.type()).isEqualTo(Envelope.EVENT_ACK);
            assertThat(ack.seq()).isEqualTo(1L);
            WhatsAppEvent published = capture.events.poll(5, TimeUnit.SECONDS);
            assertThat(published.seq()).isEqualTo(1L);
            assertThat(published.ts()).isEqualTo(1700000000000L);

            device.send(Envelope.durableEvent("message", payload, 1, 1700000000000L)); // replayed duplicate
            assertThat(device.next().seq()).isEqualTo(1L);
            assertThat(capture.events.poll(500, TimeUnit.MILLISECONDS)).isNull();

            device.send(Envelope.event("change_state", mapper.createObjectNode().put("state", "CONNECTED")));
            WhatsAppEvent transientEvent = capture.events.poll(5, TimeUnit.SECONDS);
            assertThat(transientEvent.name()).isEqualTo("change_state");
            assertThat(transientEvent.seq()).isNull();

            device.send(Envelope.ping());
            assertThat(device.next().type()).isEqualTo(Envelope.PONG); // no ack was sent for the transient event
        }
    }

    @Test
    void disconnectUnregistersDevice() throws Exception {
        FakeDevice device = FakeDevice.connectAndAuth(port, deviceToken);
        device.close();
        Thread.sleep(200); // afterConnectionClosed runs on the container thread right after the handshake
        assertThat(registry.find("demo")).isEmpty();
    }
}
