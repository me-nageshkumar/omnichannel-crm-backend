package me.nageshk.omnichannel.app.meta;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import me.nageshk.omnichannel.app.support.GraphStub;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

class WebhookSetupIT extends MetaTestBase {

    private static String subscriptions(String callback) {
        return "{\"data\":[{\"object\":\"whatsapp_business_account\",\"callback_url\":\"" + callback
                + "\",\"active\":true,\"fields\":[{\"name\":\"messages\",\"version\":\"v23.0\"}]}]}";
    }

    private static String form(GraphStub.Recorded r) {
        return URLDecoder.decode(r.body(), StandardCharsets.UTF_8);
    }

    @Test
    void noCallbackSetsOursAuto() {
        JsonNode view = connect("111");

        String key = view.get("webhook").get("url").asString().replaceFirst(".*/hooks/meta/", "");
        List<GraphStub.Recorded> set = GRAPH.recorded("POST /111/subscriptions");
        assertThat(set).hasSize(1);
        assertThat(set.get(0).authorization()).isEqualTo("Bearer 111|" + APP_SECRET);
        String body = form(set.get(0));
        assertThat(body).contains("object=whatsapp_business_account")
                .contains("callback_url=" + view.get("webhook").get("url").asString())
                .contains("verify_token=" + view.get("webhook").get("verifyToken").asString())
                .contains("fields=messages,message_template_status_update,phone_number_quality_update,"
                        + "phone_number_name_update,account_update");
        assertThat(key).isNotBlank();
        List<GraphStub.Recorded> subscribed = GRAPH.recorded("POST /222/subscribed_apps");
        assertThat(subscribed).hasSize(1);
        assertThat(subscribed.get(0).authorization()).isEqualTo("Bearer " + TOKEN);
        assertThat(subscribed.get(0).body()).doesNotContain("override_callback_uri");
        assertThat(view.get("webhook").get("setup").asString()).isEqualTo("AUTO");
    }

    @Test
    void ourCallbackAlreadyThereIsAuto() {
        String id = connect("111").get("id").asString();
        String url = meta(HttpMethod.GET, "/connections/" + id, manager, null).getBody().get("webhook").get("url")
                .asString();
        GRAPH.reset();
        GRAPH.enqueue("GET /111/subscriptions", 200, subscriptions(url));

        ResponseEntity<JsonNode> res = meta(HttpMethod.POST, "/connections/" + id + "/webhook", manager, null);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("webhook").get("setup").asString()).isEqualTo("AUTO");
        assertThat(GRAPH.recorded("POST /111/subscriptions")).isEmpty();
    }

    @Test
    void otherCallbackIsLeftAndWabaOverridden() {
        GRAPH.enqueue("GET /111/subscriptions", 200, subscriptions("https://other.example/hook"));

        JsonNode view = connect("111");

        assertThat(GRAPH.recorded("POST /111/subscriptions")).isEmpty();
        List<GraphStub.Recorded> override = GRAPH.recorded("POST /222/subscribed_apps");
        assertThat(override).hasSize(1);
        JsonNode body = mapper.readTree(override.get(0).body());
        assertThat(body.get("override_callback_uri").asString()).isEqualTo(view.get("webhook").get("url").asString());
        assertThat(body.get("verify_token").asString()).isEqualTo(view.get("webhook").get("verifyToken").asString());
        assertThat(view.get("webhook").get("setup").asString()).isEqualTo("OVERRIDE");
    }

    @Test
    void failureIsManualWithError() {
        GRAPH.enqueue("POST /111/subscriptions", 400,
                "{\"error\":{\"message\":\"Callback verification failed\",\"code\":2200}}");

        JsonNode view = connect("111");

        assertThat(view.get("webhook").get("setup").asString()).isEqualTo("MANUAL");
        assertThat(view.get("webhook").get("error").asString()).contains("Callback verification failed");
        assertThat(view.get("status").asString()).isEqualTo("OK");
    }

    @Test
    void retryEndpointRunsAgain() {
        GRAPH.enqueue("POST /111/subscriptions", 400, "{\"error\":{\"message\":\"nope\",\"code\":2200}}");
        String id = connect("111").get("id").asString();

        ResponseEntity<JsonNode> res = meta(HttpMethod.POST, "/connections/" + id + "/webhook", manager, null);

        assertThat(res.getBody().get("webhook").get("setup").asString()).isEqualTo("AUTO");
        assertThat(res.getBody().get("webhook").get("error").isNull()).isTrue();
    }

    @Test
    void invalidTokenSkipsSetup() {
        GRAPH.enqueue("GET /debug_token", 200, "{\"data\":{\"app_id\":\"111\",\"is_valid\":false}}");
        ResponseEntity<JsonNode> res = meta(HttpMethod.POST, "/connections", manager, java.util.Map.of("name", "x",
                "appId", "111", "appSecret", APP_SECRET, "accessToken", TOKEN));

        assertThat(res.getBody().get("webhook").get("setup").asString()).isEqualTo("MANUAL");
        assertThat(GRAPH.recorded("POST /111/subscriptions")).isEmpty();
    }
}
