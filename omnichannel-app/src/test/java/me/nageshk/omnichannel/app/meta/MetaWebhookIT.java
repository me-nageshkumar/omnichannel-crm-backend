package me.nageshk.omnichannel.app.meta;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

class MetaWebhookIT extends MetaTestBase {

    private JsonNode connection;
    private String key;
    private String numberId;

    @BeforeEach
    void cloudNumber() {
        mongo.remove(new Query(), "channelWebhookEvents");
        connection = connect("111");
        key = keyOf(connection);
        numberId = addCloudNumber(connection.get("id").asString(), "Admissions", "333", "+91 98765 43210").get("id")
                .asString();
    }

    private List<WaWebMessage> messagesOf(String phoneNumberId) {
        return mongo.find(new Query(Criteria.where("phoneNumberId").is(phoneNumberId)), WaWebMessage.class);
    }

    @Test
    void verifyEchoesChallenge() {
        String token = connection.get("webhook").get("verifyToken").asString();
        ResponseEntity<String> ok = rest.getForEntity(url("/hooks/meta/" + key
                + "?hub.mode=subscribe&hub.verify_token=" + token + "&hub.challenge=abc123"), String.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody()).isEqualTo("abc123");

        assertThat(rest.getForEntity(url("/hooks/meta/" + key + "?hub.mode=subscribe&hub.verify_token=nope"
                + "&hub.challenge=abc"), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rest.getForEntity(url("/hooks/meta/unknown-key?hub.mode=subscribe&hub.verify_token=" + token
                + "&hub.challenge=abc"), String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void badSignatureIs401AndStoresNothing() {
        String body = envelope(textChange("333", "919876543210", "919799750743", "wamid.A", "Hi"));

        assertThat(webhook(key, body, "wrong-secret").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(webhook(key, body, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(mongo.getCollection("channelWebhookEvents").countDocuments()).isZero();
        assertThat(messagesOf(numberId)).isEmpty();
    }

    @Test
    void oversizeBodyIs413() {
        String big = envelope(textChange("333", "919876543210", "919799750743", "wamid.A", "x".repeat(3_200_000)));

        assertThat(webhook(key, big).getStatusCode().value()).isEqualTo(413);
    }

    @Test
    void incomingTextCreatesChatAndMessage() throws Exception {
        ResponseEntity<String> res = webhook(key,
                envelope(textChange("333", "919876543210", "919799750743", "wamid.A", "Admission enquiry")));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        await(() -> !messagesOf(numberId).isEmpty());
        WaWebMessage m = messagesOf(numberId).get(0);
        assertThat(m.getWaMessageId()).isEqualTo("wamid.A");
        assertThat(m.getBody()).isEqualTo("Admission enquiry");
        WaWebChat chat = chats.findById(m.getChatId()).orElseThrow();
        assertThat(chat.getRemoteId()).isEqualTo("919799750743@c.us");
        assertThat(chat.getLastInboundAt()).isNotNull();
        JsonNode number = numbers(HttpMethod.GET, "/" + numberId, manager, null).getBody();
        JsonNode conn = meta(HttpMethod.GET, "/connections/" + connection.get("id").asString(), manager, null).getBody();
        assertThat(conn.get("webhook").get("lastWebhookAt").isNull()).isFalse();
        JsonNode listed = null;
        for (JsonNode n : numbers(HttpMethod.GET, "", manager, null).getBody()) {
            if (numberId.equals(n.get("id").asString())) listed = n;
        }
        assertThat(listed.get("cloud").get("state").asString()).isEqualTo("READY");
        assertThat(number).isNotNull();
    }

    @Test
    void routesEachChangeToItsNumber() throws Exception {
        String second = addCloudNumber(connection.get("id").asString(), "Fees", "444", "+91 98765 00000").get("id")
                .asString();

        webhook(key, envelope(textChange("333", "919876543210", "919799750743", "wamid.A", "one"),
                textChange("444", "919876500000", "919799750744", "wamid.B", "two")));

        await(() -> messagesOf(numberId).size() == 1 && messagesOf(second).size() == 1);
        assertThat(messagesOf(numberId).get(0).getBody()).isEqualTo("one");
        assertThat(messagesOf(second).get(0).getBody()).isEqualTo("two");
    }

    @Test
    void dropsUnknownNumberKeepsTheRest() throws Exception {
        ResponseEntity<String> res = webhook(key, envelope(
                textChange("333", "919876543210", "919799750743", "wamid.A", "kept"),
                textChange("999", "919000000000", "919799750743", "wamid.Z", "dropped")));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        await(() -> messagesOf(numberId).size() == 1);
        JsonNode conn = meta(HttpMethod.GET, "/connections/" + connection.get("id").asString(), manager, null).getBody();
        assertThat(conn.get("ignoredChanges").asLong()).isEqualTo(1);
        assertThat(conn.get("lastIgnoredNumber").asString()).isEqualTo("999");
        assertThat(mongo.getCollection("channelWebhookEvents").countDocuments()).isEqualTo(1);
    }

    @Test
    void numberOfAnotherConnectionIsDropped() throws Exception {
        GRAPH.enqueue("GET /debug_token", 200, me.nageshk.omnichannel.app.support.GraphStub.validToken("112", "222"));
        JsonNode other = meta(HttpMethod.POST, "/connections", manager, java.util.Map.of("name", "Second app",
                "appId", "112", "appSecret", "second-secret-000000000", "accessToken", TOKEN)).getBody();

        // signed by the second app, but number 333 belongs to the first
        ResponseEntity<String> res = webhook(keyOf(other),
                envelope(textChange("333", "919876543210", "919799750743", "wamid.A", "wrong app")),
                "second-secret-000000000");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        Thread.sleep(300);
        assertThat(messagesOf(numberId)).isEmpty();
    }

    @Test
    void retryIsProcessedOnce() throws Exception {
        String body = envelope(textChange("333", "919876543210", "919799750743", "wamid.A", "once"));

        webhook(key, body);
        await(() -> messagesOf(numberId).size() == 1);
        assertThat(webhook(key, body).getStatusCode()).isEqualTo(HttpStatus.OK);
        Thread.sleep(300);

        assertThat(messagesOf(numberId)).hasSize(1);
        assertThat(mongo.getCollection("channelWebhookEvents").countDocuments()).isEqualTo(1);
        WaWebChat chat = chats.findById(messagesOf(numberId).get(0).getChatId()).orElseThrow();
        assertThat(chat.getUnreadCount()).isEqualTo(1);
    }

    @Test
    void qualityUpdateRefreshesAccount() throws Exception {
        GRAPH.enqueue("GET /333", 200, "{\"id\":\"333\",\"display_phone_number\":\"+91 98765 43210\","
                + "\"quality_rating\":\"YELLOW\",\"status\":\"CONNECTED\",\"host_platform\":\"CLOUD_API\"}");

        webhook(key, envelope("{\"field\":\"phone_number_quality_update\",\"value\":{\"display_phone_number\":"
                + "\"919876543210\",\"event\":\"FLAGGED\",\"max_daily_conversations_per_business\":\"TIER_2K\"}}"));

        await(() -> "YELLOW".equals(mongo.getCollection("cloudAccounts").find().first().getString("qualityRating")));
        assertThat(mongo.getCollection("cloudAccounts").find().first().getString("messagingLimitTier"))
                .isEqualTo("TIER_2K");
    }
}
