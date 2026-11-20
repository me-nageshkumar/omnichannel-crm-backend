package me.nageshk.omnichannel.app.meta;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import me.nageshk.omnichannel.app.support.GraphStub;
import me.nageshk.omnichannel.db.user.User;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

@ExtendWith(OutputCaptureExtension.class)
class MetaConnectionsApiIT extends MetaTestBase {

    @Test
    void createSealsSecretsAndShowsHintsOnly(CapturedOutput output) {
        JsonNode view = connect("111");

        assertThat(view.get("appId").asString()).isEqualTo("111");
        assertThat(view.get("status").asString()).isEqualTo("OK");
        assertThat(view.get("tokenHint").asString()).isEqualTo("EAA…1234");
        assertThat(view.get("appSecretHint").asString()).isEqualTo("app…cdef");
        assertThat(view.has("accessToken")).isFalse();
        assertThat(view.has("appSecret")).isFalse();
        assertThat(view.toString()).doesNotContain(TOKEN).doesNotContain(APP_SECRET);
        assertThat(view.get("webhook").get("url").asString()).contains("/hooks/meta/");
        assertThat(view.get("webhook").get("verifyToken").asString()).hasSizeGreaterThanOrEqualTo(24);

        Document stored = mongo.getCollection("metaConnections").find().first();
        assertThat(stored.toJson()).doesNotContain(TOKEN).doesNotContain(APP_SECRET);
        assertThat(stored.get("accessToken", Document.class).getString("ciphertext")).isNotBlank();
        assertThat(meta(HttpMethod.GET, "/connections", manager, null).getBody().toString())
                .doesNotContain(TOKEN).doesNotContain(APP_SECRET);
        assertThat(output.getAll()).doesNotContain(TOKEN).doesNotContain(APP_SECRET);
        // the check asked Meta with the app token, never logging it
        assertThat(GRAPH.recorded("GET /debug_token")).hasSize(1);
    }

    @Test
    void putWithoutSecretsKeepsThem() {
        String id = connect("111").get("id").asString();
        GRAPH.reset();

        ResponseEntity<JsonNode> res = meta(HttpMethod.PUT, "/connections/" + id, manager, Map.of("name", "Renamed"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("name").asString()).isEqualTo("Renamed");
        assertThat(res.getBody().get("tokenHint").asString()).isEqualTo("EAA…1234");
        assertThat(GRAPH.recorded()).isEmpty();
    }

    @Test
    void putWithTokenReplacesItAndRechecks() {
        String id = connect("111").get("id").asString();
        GRAPH.reset();
        GRAPH.enqueue("GET /debug_token", 200, GraphStub.validToken("111", "222"));

        ResponseEntity<JsonNode> res = meta(HttpMethod.PUT, "/connections/" + id, manager,
                Map.of("name", "Bimal app", "accessToken", "EAAGnewtoken-99999999zz9876"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("tokenHint").asString()).isEqualTo("EAA…9876");
        assertThat(GRAPH.recorded("GET /debug_token").get(0).query()).contains("input_token=EAAGnewtoken");
    }

    @Test
    void sameAppInAnotherBrandIs409() {
        connect("111");
        User otherManager = brandUser(other, "other-manager", "WORKSPACE_MANAGER");
        GRAPH.enqueue("GET /debug_token", 200, GraphStub.validToken("111", "222"));

        ResponseEntity<JsonNode> res = rest.exchange(url("/api/workspaces/" + other.getId() + "/meta/connections"),
                HttpMethod.POST, auth(otherManager, Map.of("name", "Same app", "appId", "111", "appSecret", APP_SECRET,
                        "accessToken", TOKEN)), JsonNode.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().get("field").asString()).isEqualTo("appId");
    }

    @Test
    void invalidTokenSavesAsInvalid() {
        GRAPH.enqueue("GET /debug_token", 200, "{\"data\":{\"app_id\":\"111\",\"is_valid\":false,"
                + "\"error\":{\"code\":190,\"message\":\"Session has expired\"}}}");

        ResponseEntity<JsonNode> res = meta(HttpMethod.POST, "/connections", manager,
                Map.of("name", "Bimal app", "appId", "111", "appSecret", APP_SECRET, "accessToken", TOKEN));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody().get("status").asString()).isEqualTo("INVALID");
        assertThat(res.getBody().get("lastError").asString()).isNotBlank();
    }

    @Test
    void tokenOfAnotherAppIsInvalid() {
        GRAPH.enqueue("GET /debug_token", 200, GraphStub.validToken("999", "222"));

        ResponseEntity<JsonNode> res = meta(HttpMethod.POST, "/connections", manager,
                Map.of("name", "Bimal app", "appId", "111", "appSecret", APP_SECRET, "accessToken", TOKEN));

        assertThat(res.getBody().get("status").asString()).isEqualTo("INVALID");
    }

    @Test
    void missingFieldsAre400() {
        Map<String, Object> body = new HashMap<>();
        body.put("name", " ");
        ResponseEntity<JsonNode> res = meta(HttpMethod.POST, "/connections", manager, body);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void checkListsWabasAndNumbers() {
        String id = connect("111").get("id").asString();
        GRAPH.enqueue("GET /debug_token", 200, GraphStub.validToken("111", "222"));
        GRAPH.enqueue("GET /222/phone_numbers", 200, "{\"data\":[{\"id\":\"333\",\"display_phone_number\":"
                + "\"+91 98765 43210\",\"verified_name\":\"Bimal Institute\",\"name_status\":\"APPROVED\","
                + "\"quality_rating\":\"GREEN\",\"platform_type\":\"CLOUD_API\"}]}");
        GRAPH.enqueue("GET /222", 200, "{\"id\":\"222\",\"name\":\"Bimal WABA\"}");

        ResponseEntity<JsonNode> res = meta(HttpMethod.POST, "/connections/" + id + "/check", manager, null);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode account = res.getBody().get("accounts").get(0);
        assertThat(account.get("wabaId").asString()).isEqualTo("222");
        assertThat(account.get("wabaName").asString()).isEqualTo("Bimal WABA");
        JsonNode number = account.get("numbers").get(0);
        assertThat(number.get("metaPhoneNumberId").asString()).isEqualTo("333");
        assertThat(number.get("displayPhoneNumber").asString()).isEqualTo("+91 98765 43210");
        assertThat(number.get("verifiedName").asString()).isEqualTo("Bimal Institute");
        assertThat(number.get("phoneNumberId").isNull()).isTrue();
    }

    @Test
    void deleteUnusedIs204() {
        String id = connect("111").get("id").asString();

        assertThat(meta(HttpMethod.DELETE, "/connections/" + id, manager, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(meta(HttpMethod.GET, "/connections", manager, null).getBody().size()).isZero();
    }

    @Test
    void agentCannotManage() {
        ResponseEntity<JsonNode> res = meta(HttpMethod.POST, "/connections", agent,
                Map.of("name", "Bimal app", "appId", "111", "appSecret", APP_SECRET, "accessToken", TOKEN));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
