package me.nageshk.omnichannel.app.meta;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import me.nageshk.omnichannel.app.chatbot.ChatbotTestBase;
import me.nageshk.omnichannel.app.support.GraphStub;
import me.nageshk.omnichannel.db.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/** The chatbot test world plus a local Graph API stand-in and helpers for Meta connections. */
public abstract class MetaTestBase extends ChatbotTestBase {

    protected static final GraphStub GRAPH = new GraphStub();
    protected static final String APP_SECRET = "app-secret-0123456789abcdef";
    protected static final String TOKEN = "EAAGtoken-0123456789abcdef1234";

    @DynamicPropertySource
    static void graph(DynamicPropertyRegistry registry) {
        registry.add("omnichannel.meta.graph-base-url", GRAPH::baseUrl);
    }

    @BeforeEach
    void resetMeta() {
        GRAPH.reset();
        for (String c : new String[] {"metaConnections", "cloudAccounts", "cloudTemplates"}) {
            mongo.remove(new Query(), c);
        }
    }

    /** {@code path} is relative to the brand's {@code /api/workspaces/{id}/meta}. */
    protected ResponseEntity<JsonNode> meta(HttpMethod m, String path, User who, Object body) {
        return rest.exchange(url("/api/workspaces/" + brand.getId() + "/meta" + path), m, auth(who, body),
                JsonNode.class);
    }

    /** A connection of the brand whose token Meta accepts, for app {@code appId} on WABA {@code 222}. */
    protected JsonNode connect(String appId) {
        GRAPH.enqueue("GET /debug_token", 200, GraphStub.validToken(appId, "222"));
        ResponseEntity<JsonNode> res = meta(HttpMethod.POST, "/connections", manager,
                Map.of("name", "Bimal app", "appId", appId, "appSecret", APP_SECRET, "accessToken", TOKEN));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return res.getBody();
    }

    /** Stubs Meta's answers for adding {@code metaId} (in WABA 222) as a registered Cloud number. */
    protected static void stubNumber(String metaId, String display, boolean registered, String... alsoInWaba) {
        StringBuilder list = new StringBuilder("{\"data\":[{\"id\":\"" + metaId + "\"}");
        for (String other : alsoInWaba) list.append(",{\"id\":\"").append(other).append("\"}");
        list.append("]}");
        GRAPH.enqueue("GET /" + metaId, 200, "{\"id\":\"" + metaId + "\",\"display_phone_number\":\"" + display
                + "\",\"verified_name\":\"Bimal Institute\",\"name_status\":\"APPROVED\",\"quality_rating\":"
                + "\"GREEN\",\"code_verification_status\":\"VERIFIED\",\"status\":\""
                + (registered ? "CONNECTED" : "PENDING") + "\",\"host_platform\":\""
                + (registered ? "CLOUD_API" : "NOT_APPLICABLE") + "\"}");
        GRAPH.enqueue("GET /222/phone_numbers", 200, list.toString());
        GRAPH.enqueue("GET /222", 200, "{\"id\":\"222\",\"whatsapp_business_manager_messaging_limit\":\"TIER_250\"}");
    }

    /** Adds a registered Cloud number of {@code connectionId}; returns the number's view. */
    protected JsonNode addCloudNumber(String connectionId, String label, String metaId, String display) {
        stubNumber(metaId, display, true);
        ResponseEntity<JsonNode> res = rest.exchange(url("/api/workspaces/" + brand.getId() + "/phone-numbers"),
                HttpMethod.POST, auth(manager, Map.of("label", label, "connectionType", "WHATSAPP_CLOUD_API", "cloud",
                        Map.of("connectionId", connectionId, "wabaId", "222", "metaPhoneNumberId", metaId))),
                JsonNode.class);
        assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.CREATED);
        return res.getBody();
    }

    protected ResponseEntity<JsonNode> numbers(HttpMethod m, String path, User who, Object body) {
        return rest.exchange(url("/api/workspaces/" + brand.getId() + "/phone-numbers" + path), m, auth(who, body),
                JsonNode.class);
    }
}
