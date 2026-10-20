package me.nageshk.omnichannel.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.nageshk.omnichannel.auth.JwtService;
import me.nageshk.omnichannel.support.TestUsers;
import me.nageshk.omnichannel.rpc.Envelope;
import me.nageshk.omnichannel.support.FakeDevice;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "omnichannel.rpc.timeout=2s")
class MessagesApiIT {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JwtService jwt;
    @Autowired TestUsers testUsers;
    @Autowired me.nageshk.omnichannel.support.TestDevices testDevices;
    String deviceToken;
    String deviceKey;
    String bridge;

    @BeforeEach
    void seedDeviceUser() {
        testUsers.deleteAll();
        deviceToken = testUsers.accessToken(testUsers.create("root", "pw", "SUPER_ADMIN", null));
        var connected = testDevices.connected("919928088743");
        deviceKey = connected.deviceKey();
        bridge = "/api/phone-numbers/" + connected.phoneNumberId() + "/bridge";
    }

    private ObjectNode ready() {
        return mapper.createObjectNode().put("pushname", "N").put("wid", "919928088743@c.us");
    }
    @Autowired ObjectMapper mapper;

    private HttpHeaders authHeaders(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private ResponseEntity<JsonNode> postMessage(String token, String body) {
        return rest.exchange(bridge + "/messages", HttpMethod.POST, new HttpEntity<>(body, authHeaders(token)), JsonNode.class);
    }

    @Test
    void sendWithoutDeviceReturns409() {
        ResponseEntity<JsonNode> res = postMessage(deviceToken,
                "{\"chatId\":\"1@c.us\",\"content\":\"hi\"}");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().get("code").asText()).isEqualTo("NO_DEVICE");
    }

    @Test
    void sendWhenWhatsAppNotReadyReturns409() throws Exception {
        String token = deviceToken;
        try (FakeDevice device = FakeDevice.connectAndAuth(port, deviceKey)) {
            ResponseEntity<JsonNode> res = postMessage(token, "{\"chatId\":\"1@c.us\",\"content\":\"hi\"}");
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(res.getBody().get("code").asText()).isEqualTo("WHATSAPP_NOT_READY");
        }
    }

    @Test
    void sendRoundTripsThroughDevice() throws Exception {
        String token = deviceToken;
        try (FakeDevice device = FakeDevice.connectAndAuth(port, deviceKey)) {
            device.send(Envelope.event("ready", ready()));
            Thread.sleep(200);

            CompletableFuture<ResponseEntity<JsonNode>> http = CompletableFuture.supplyAsync(() ->
                    postMessage(token, "{\"chatId\":\"1@c.us\",\"content\":\"hello\",\"options\":{\"linkPreview\":false}}"));

            Envelope req = device.next();
            assertThat(req.type()).isEqualTo(Envelope.REQUEST);
            assertThat(req.method()).isEqualTo("sendMessage");
            assertThat(req.params().get("chatId").asText()).isEqualTo("1@c.us");
            assertThat(req.params().get("content").asText()).isEqualTo("hello");
            assertThat(req.params().get("options").get("linkPreview").asBoolean()).isFalse();
            assertThat(req.params().get("sendSeen").asBoolean()).isFalse();

            var result = mapper.createObjectNode().put("body", "hello");
            result.set("id", mapper.createObjectNode().put("_serialized", "true_1@c.us_ABC").put("fromMe", true));
            device.send(Envelope.response(req.id(), result));

            ResponseEntity<JsonNode> res = http.get(5, TimeUnit.SECONDS);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(res.getBody().get("id").get("_serialized").asText()).isEqualTo("true_1@c.us_ABC");
        }
    }

    @Test
    void pageErrorMapsTo400() throws Exception {
        String token = deviceToken;
        try (FakeDevice device = FakeDevice.connectAndAuth(port, deviceKey)) {
            device.send(Envelope.event("ready", ready()));
            Thread.sleep(200);

            CompletableFuture<ResponseEntity<JsonNode>> http = CompletableFuture.supplyAsync(() ->
                    postMessage(token, "{\"chatId\":\"bad\",\"content\":\"x\"}"));
            Envelope req = device.next();
            device.send(Envelope.errorResponse(req.id(), "CHAT_NOT_FOUND", "Chat not found: bad"));

            ResponseEntity<JsonNode> res = http.get(5, TimeUnit.SECONDS);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(res.getBody().get("code").asText()).isEqualTo("CHAT_NOT_FOUND");
            assertThat(res.getBody().get("message").asText()).isEqualTo("Chat not found: bad");
        }
    }

    @Test
    void noResponseMapsTo504() throws Exception {
        String token = deviceToken;
        try (FakeDevice device = FakeDevice.connectAndAuth(port, deviceKey)) {
            device.send(Envelope.event("ready", ready()));
            Thread.sleep(200);

            ResponseEntity<JsonNode> res = postMessage(token, "{\"chatId\":\"1@c.us\",\"content\":\"x\"}");
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
            assertThat(res.getBody().get("code").asText()).isEqualTo("TIMEOUT");
        }
    }

    @Test
    void validationErrorMapsTo400() {
        ResponseEntity<JsonNode> res = postMessage(deviceToken, "{\"content\":\"x\"}");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("code").asText()).isEqualTo("VALIDATION");
    }

    @Test
    void statusReflectsDevice() throws Exception {
        String token = deviceToken;
        ResponseEntity<JsonNode> before = rest.exchange(bridge + "/status", HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)), JsonNode.class);
        assertThat(before.getBody().get("deviceConnected").asBoolean()).isFalse();

        try (FakeDevice device = FakeDevice.connectAndAuth(port, deviceKey)) {
            device.send(Envelope.event("ready", ready()));
            Thread.sleep(200);
            ResponseEntity<JsonNode> after = rest.exchange(bridge + "/status", HttpMethod.GET,
                    new HttpEntity<>(authHeaders(token)), JsonNode.class);
            assertThat(after.getBody().get("deviceConnected").asBoolean()).isTrue();
            assertThat(after.getBody().get("waState").asText()).isEqualTo("CONNECTED");
            assertThat(after.getBody().get("info").get("pushname").asText()).isEqualTo("N");
        }
    }

    @Test
    void rpcPassesAnyMethodThroughWithoutReadinessGate() throws Exception {
        String token = deviceToken;
        try (FakeDevice device = FakeDevice.connectAndAuth(port, deviceKey)) {
            CompletableFuture<ResponseEntity<JsonNode>> http = CompletableFuture.supplyAsync(() ->
                    rest.exchange(bridge + "/rpc", HttpMethod.POST,
                            new HttpEntity<>("{\"method\":\"getState\"}", authHeaders(token)), JsonNode.class));

            Envelope req = device.next();
            assertThat(req.method()).isEqualTo("getState");
            device.send(Envelope.response(req.id(), mapper.getNodeFactory().textNode("CONNECTED")));

            ResponseEntity<JsonNode> res = http.get(5, TimeUnit.SECONDS);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(res.getBody().asText()).isEqualTo("CONNECTED");
        }
    }

    @Test
    void rpcWithoutDeviceReturns409() {
        ResponseEntity<JsonNode> res = rest.exchange(bridge + "/rpc", HttpMethod.POST,
                new HttpEntity<>("{\"method\":\"getState\"}", authHeaders(deviceToken)), JsonNode.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().get("code").asText()).isEqualTo("NO_DEVICE");
    }

    @Test
    void eventsEndpointReturnsRecentEventsInOrderWithSeqCursor() throws Exception {
        String token = deviceToken;
        try (FakeDevice device = FakeDevice.connectAndAuth(port, deviceKey)) {
            device.send(Envelope.event("ready", ready()));
            device.send(Envelope.event("message", mapper.createObjectNode().put("body", "one")));
            device.send(Envelope.durableEvent("message", mapper.createObjectNode().put("body", "two"), 42, 1700000000000L));
            Thread.sleep(300);

            ResponseEntity<JsonNode> res = rest.exchange(bridge + "/events?limit=2", HttpMethod.GET,
                    new HttpEntity<>(authHeaders(token)), JsonNode.class);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode list = res.getBody();
            assertThat(list.size()).isEqualTo(2);
            assertThat(list.get(0).get("name").asText()).isEqualTo("message");
            assertThat(list.get(0).get("payload").get("body").asText()).isEqualTo("one");
            assertThat(list.get(1).get("payload").get("body").asText()).isEqualTo("two");
            assertThat(list.get(1).get("deviceSeq").asLong()).isEqualTo(42L);
            assertThat(list.get(1).get("capturedAt").asText()).isEqualTo("2023-11-14T22:13:20Z");
            assertThat(list.get(0).get("deviceSeq").isNull()).isTrue();

            long last = list.get(1).get("seq").asLong();
            ResponseEntity<JsonNode> none = rest.exchange(bridge + "/events?after=" + last, HttpMethod.GET,
                    new HttpEntity<>(authHeaders(token)), JsonNode.class);
            assertThat(none.getBody().size()).isEqualTo(0);
        }
    }

    @Test
    void statusWithoutTokenIs401() {
        ResponseEntity<Map> res = rest.getForEntity(bridge + "/status", Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void bridgeEndpointsAreSuperAdminOnly() {
        var acme = testUsers.customer("Acme");
        String admin = testUsers.accessToken(
                testUsers.createInCustomer(acme.getId(), "admin", "CUSTOMER_ADMIN", List.of()));
        String am = testUsers.accessToken(testUsers.create("am", "pw", "ACCOUNT_MANAGER", null));

        var calls = Map.of(
                bridge + "/messages", HttpMethod.POST,
                bridge + "/rpc", HttpMethod.POST,
                bridge + "/status", HttpMethod.GET,
                bridge + "/events", HttpMethod.GET);
        for (String token : List.of(admin, am)) {
            calls.forEach((path, method) -> {
                String body = method == HttpMethod.POST ? "{\"method\":\"getState\",\"chatId\":\"1@c.us\",\"content\":\"hi\"}" : null;
                ResponseEntity<JsonNode> res = rest.exchange(path, method, new HttpEntity<>(body, authHeaders(token)), JsonNode.class);
                assertThat(res.getStatusCode()).as(path).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(res.getBody().get("code").asText()).as(path).isEqualTo("FORBIDDEN");
            });
        }
    }

    @Test
    void oldPathsAreGone() {
        ResponseEntity<JsonNode> res = rest.exchange("/api/status", HttpMethod.GET,
                new HttpEntity<>(authHeaders(deviceToken)), JsonNode.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
