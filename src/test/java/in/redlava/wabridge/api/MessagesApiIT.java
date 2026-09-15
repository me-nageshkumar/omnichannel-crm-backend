package in.redlava.wabridge.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.redlava.wabridge.auth.JwtService;
import in.redlava.wabridge.rpc.Envelope;
import in.redlava.wabridge.support.FakeDevice;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
        properties = "wabridge.rpc.timeout=2s")
class MessagesApiIT {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper mapper;

    private HttpHeaders authHeaders(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private ResponseEntity<JsonNode> postMessage(String token, String body) {
        return rest.exchange("/api/messages", HttpMethod.POST, new HttpEntity<>(body, authHeaders(token)), JsonNode.class);
    }

    @Test
    void sendWithoutDeviceReturns409() {
        ResponseEntity<JsonNode> res = postMessage(jwt.issue("demo").token(),
                "{\"chatId\":\"1@c.us\",\"content\":\"hi\"}");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().get("code").asText()).isEqualTo("NO_DEVICE");
    }

    @Test
    void sendWhenWhatsAppNotReadyReturns409() throws Exception {
        String token = jwt.issue("demo").token();
        try (FakeDevice device = FakeDevice.connectAndAuth(port, token)) {
            ResponseEntity<JsonNode> res = postMessage(token, "{\"chatId\":\"1@c.us\",\"content\":\"hi\"}");
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(res.getBody().get("code").asText()).isEqualTo("WHATSAPP_NOT_READY");
        }
    }

    @Test
    void sendRoundTripsThroughDevice() throws Exception {
        String token = jwt.issue("demo").token();
        try (FakeDevice device = FakeDevice.connectAndAuth(port, token)) {
            device.send(Envelope.event("ready", mapper.createObjectNode().put("pushname", "N")));
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
        String token = jwt.issue("demo").token();
        try (FakeDevice device = FakeDevice.connectAndAuth(port, token)) {
            device.send(Envelope.event("ready", mapper.createObjectNode()));
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
        String token = jwt.issue("demo").token();
        try (FakeDevice device = FakeDevice.connectAndAuth(port, token)) {
            device.send(Envelope.event("ready", mapper.createObjectNode()));
            Thread.sleep(200);

            ResponseEntity<JsonNode> res = postMessage(token, "{\"chatId\":\"1@c.us\",\"content\":\"x\"}");
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
            assertThat(res.getBody().get("code").asText()).isEqualTo("TIMEOUT");
        }
    }

    @Test
    void validationErrorMapsTo400() {
        ResponseEntity<JsonNode> res = postMessage(jwt.issue("demo").token(), "{\"content\":\"x\"}");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("code").asText()).isEqualTo("VALIDATION");
    }

    @Test
    void statusReflectsDevice() throws Exception {
        String token = jwt.issue("demo").token();
        ResponseEntity<JsonNode> before = rest.exchange("/api/status", HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)), JsonNode.class);
        assertThat(before.getBody().get("deviceConnected").asBoolean()).isFalse();

        try (FakeDevice device = FakeDevice.connectAndAuth(port, token)) {
            device.send(Envelope.event("ready", mapper.createObjectNode().put("pushname", "N")));
            Thread.sleep(200);
            ResponseEntity<JsonNode> after = rest.exchange("/api/status", HttpMethod.GET,
                    new HttpEntity<>(authHeaders(token)), JsonNode.class);
            assertThat(after.getBody().get("deviceConnected").asBoolean()).isTrue();
            assertThat(after.getBody().get("waState").asText()).isEqualTo("CONNECTED");
            assertThat(after.getBody().get("info").get("pushname").asText()).isEqualTo("N");
        }
    }

    @Test
    void rpcPassesAnyMethodThroughWithoutReadinessGate() throws Exception {
        String token = jwt.issue("demo").token();
        try (FakeDevice device = FakeDevice.connectAndAuth(port, token)) {
            CompletableFuture<ResponseEntity<JsonNode>> http = CompletableFuture.supplyAsync(() ->
                    rest.exchange("/api/rpc", HttpMethod.POST,
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
        ResponseEntity<JsonNode> res = rest.exchange("/api/rpc", HttpMethod.POST,
                new HttpEntity<>("{\"method\":\"getState\"}", authHeaders(jwt.issue("demo").token())), JsonNode.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().get("code").asText()).isEqualTo("NO_DEVICE");
    }

    @Test
    void eventsEndpointReturnsRecentEventsInOrderWithSeqCursor() throws Exception {
        String token = jwt.issue("demo").token();
        try (FakeDevice device = FakeDevice.connectAndAuth(port, token)) {
            device.send(Envelope.event("ready", mapper.createObjectNode().put("pushname", "N")));
            device.send(Envelope.event("message", mapper.createObjectNode().put("body", "one")));
            device.send(Envelope.durableEvent("message", mapper.createObjectNode().put("body", "two"), 42, 1700000000000L));
            Thread.sleep(300);

            ResponseEntity<JsonNode> res = rest.exchange("/api/events?limit=2", HttpMethod.GET,
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
            ResponseEntity<JsonNode> none = rest.exchange("/api/events?after=" + last, HttpMethod.GET,
                    new HttpEntity<>(authHeaders(token)), JsonNode.class);
            assertThat(none.getBody().size()).isEqualTo(0);
        }
    }

    @Test
    void statusWithoutTokenIs401() {
        ResponseEntity<Map> res = rest.getForEntity("/api/status", Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
