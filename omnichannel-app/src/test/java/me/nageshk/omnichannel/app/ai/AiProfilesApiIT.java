package me.nageshk.omnichannel.app.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.db.access.Role;
import me.nageshk.omnichannel.db.access.RoleLevel;
import me.nageshk.omnichannel.db.access.RoleRepository;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.db.user.WorkspaceRole;
import me.nageshk.omnichannel.security.access.RoleCache;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

class AiProfilesApiIT extends AiTestBase {

    private static final String KEY = "sk-test-1234567890a91F";
    private static final String SECRET = "mcp-secret-value-98765";

    @Autowired RoleRepository roles;
    @Autowired RoleCache roleCache;

    private String connection(String name, String provider) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("name", name);
        b.put("provider", provider);
        b.put("apiKey", "openai".equals(provider) ? KEY : "good");
        return ai(HttpMethod.POST, "/connections", manager, b).getBody().get("id").asString();
    }

    private static Map<String, Object> mcp(String label, String url, String connectBy, String auth, String secret) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("url", url);
        m.put("connectBy", connectBy);
        m.put("auth", auth);
        if (secret != null) m.put("secret", secret);
        return m;
    }

    private static Map<String, Object> profile(String name, String connectionId) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("name", name);
        b.put("connectionId", connectionId);
        b.put("model", "gpt-5");
        return b;
    }

    private ResponseEntity<JsonNode> create(Map<String, Object> body) {
        return ai(HttpMethod.POST, "/profiles", manager, body);
    }

    private String field(ResponseEntity<JsonNode> res, String path) {
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode m = res.getBody().get("fields").get(path);
        assertThat(m).as("field " + path + " in " + res.getBody()).isNotNull();
        return m.asString();
    }

    private User roleUser(String code, String prefix) {
        Role r = new Role();
        r.setName(prefix + COUNTER.incrementAndGet());
        r.setLevel(RoleLevel.BRAND);
        r.setAuthorities(Set.of(code));
        r = roles.save(r);
        roleCache.evictAll();
        return testUsers.createInCustomer(acme.getId(), prefix.toLowerCase() + COUNTER.incrementAndGet(), null,
                List.of(new WorkspaceRole(brand.getId(), r.getId())));
    }

    private void chatbotWithAiNode(String name, String profileId, boolean draft, boolean version) {
        Map<String, Object> flow = Map.of("nodes", List.of(
                Map.of("id", "n1", "type", "wait", "data", Map.of("name", "W", "settings", Map.of())),
                Map.of("id", "n2", "type", "ai", "data", Map.of("name", "Ask", "settings", Map.of("profileId", profileId)))));
        Map<String, Object> empty = Map.of("nodes", List.of());
        org.bson.Document bot = new org.bson.Document("workspaceId", brand.getId()).append("name", name)
                .append("status", "DRAFT").append("flow", draft ? flow : empty).append("versionNumber", 1);
        String id = mongo.insert(bot, "chatbots").getObjectId("_id").toHexString();
        mongo.insert(new org.bson.Document("workspaceId", brand.getId()).append("chatbotId", id)
                .append("versionNumber", 1).append("flow", version ? flow : empty).append("savedAt", Instant.now()),
                "chatbotVersions");
    }

    @Test
    void createAndRead() {
        String conn = connection("Main", "openai");
        Map<String, Object> b = profile("Support", conn);
        b.put("instructions", "Be kind.");
        b.put("vectorStoreIds", List.of("vs_1", "vs_2"));
        b.put("mcpServers", List.of(mcp("crm", "https://mcp.example.com/sse", "PROVIDER", "BEARER", SECRET)));

        ResponseEntity<JsonNode> res = create(b);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode v = res.getBody();
        assertThat(v.get("name").asString()).isEqualTo("Support");
        assertThat(v.get("provider").asString()).isEqualTo("openai");
        assertThat(v.get("model").asString()).isEqualTo("gpt-5");
        assertThat(v.get("vectorStoreIds")).hasSize(2);
        JsonNode s = v.get("mcpServers").get(0);
        assertThat(s.get("id").asString()).isNotBlank();
        assertThat(s.get("secretHint").asString()).isNotBlank();
        assertThat(s.get("secretSetAt").isNull()).isFalse();
        assertThat(s.has("secret")).isFalse();
        assertThat(res.getBody().toString()).doesNotContain(SECRET);
        String id = v.get("id").asString();
        assertThat(ai(HttpMethod.GET, "/profiles", manager, null).getBody().toString()).doesNotContain(SECRET);
        assertThat(ai(HttpMethod.GET, "/profiles/" + id, manager, null).getBody().toString()).doesNotContain(SECRET);
        org.bson.Document stored = mongo.findOne(new Query(), org.bson.Document.class, "aiProfiles");
        assertThat(stored.toJson()).doesNotContain(SECRET);
        assertThat(stored.getString("connectionId")).isEqualTo(conn);
        assertThat(stored.getString("workspaceId")).isEqualTo(brand.getId());
    }

    @Test
    void mcpLabelRules() {
        String conn = connection("Main", "openai");
        Map<String, Object> b = profile("P", conn);
        b.put("mcpServers", List.of(mcp("ok_1", "https://a.example.com/x", "PROVIDER", "NONE", null),
                mcp("Bad Label", "https://b.example.com/x", "PROVIDER", "NONE", null)));
        field(create(b), "mcpServers.1.label");

        b.put("mcpServers", List.of(mcp("same", "https://a.example.com/x", "PROVIDER", "NONE", null),
                mcp("same", "https://b.example.com/x", "PROVIDER", "NONE", null)));
        field(create(b), "mcpServers.1.label");

        b.put("mcpServers", List.of(mcp("a23456789012345678901", "https://a.example.com/x", "PROVIDER", "NONE", null)));
        field(create(b), "mcpServers.0.label");
    }

    @Test
    void mcpUrlMustBeHttps() {
        String conn = connection("Main", "openai");
        Map<String, Object> b = profile("P", conn);
        b.put("mcpServers", List.of(mcp("a", "http://a.example.com/x", "PROVIDER", "NONE", null)));
        field(create(b), "mcpServers.0.url");
        b.put("mcpServers", List.of(mcp("a", "not a url", "PROVIDER", "NONE", null)));
        field(create(b), "mcpServers.0.url");
    }

    @Test
    void mcpAuthRules() {
        String conn = connection("Main", "openai");
        Map<String, Object> b = profile("P", conn);
        b.put("mcpServers", List.of(mcp("a", "https://a.example.com/x", "PROVIDER", "BEARER", null)));
        field(create(b), "mcpServers.0.secret");

        Map<String, Object> h = mcp("a", "https://a.example.com/x", "PROVIDER", "HEADER", SECRET);
        b.put("mcpServers", List.of(h));
        field(create(b), "mcpServers.0.headerName");
        h.put("headerName", "bad header");
        field(create(b), "mcpServers.0.headerName");
        h.put("headerName", "X-Api-Key");
        assertThat(create(b).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void appServerAllowedForAProviderWithoutHostedMcp() {
        String conn = connection("Fake", "scripted");
        Map<String, Object> b = profile("P", conn);
        b.put("mcpServers", List.of(mcp("a", "https://a.example.com/x", "APP", "NONE", null)));
        assertThat(create(b).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void capabilityChecks() {
        String conn = connection("Fake", "scripted");

        Map<String, Object> b = profile("P", conn);
        b.put("mcpServers", List.of(mcp("a", "https://a.example.com/x", "PROVIDER", "NONE", null)));
        assertThat(field(create(b), "mcpServers.0.connectBy"))
                .isEqualTo("This provider cannot connect to MCP servers itself; choose Our app");

        b = profile("P", conn);
        b.put("vectorStoreIds", List.of("vs_1"));
        assertThat(field(create(b), "vectorStoreIds")).isEqualTo("This provider has no vector stores");

        b = profile("P", conn);
        b.put("temperature", 0.5);
        assertThat(field(create(b), "temperature")).isEqualTo("This provider does not take a temperature");

        b = profile("P", conn);
        b.put("webSearch", true);
        assertThat(field(create(b), "webSearch")).isEqualTo("This provider has no web search");

        b = profile("P", conn);
        b.put("reasoningEffort", "low");
        assertThat(field(create(b), "reasoningEffort")).isEqualTo("This provider does not take a reasoning effort");

        assertThat(create(profile("P", conn)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void ranges() {
        String conn = connection("Main", "openai");
        Map<String, Object> b = profile("P", conn);
        b.put("maxOutputTokens", 0);
        field(create(b), "maxOutputTokens");
        b.put("maxOutputTokens", 128001);
        field(create(b), "maxOutputTokens");
        b.remove("maxOutputTokens");
        b.put("temperature", 2.1);
        field(create(b), "temperature");
        b.put("temperature", -0.1);
        field(create(b), "temperature");
        b.remove("temperature");
        b.put("reasoningEffort", "extreme");
        field(create(b), "reasoningEffort");
        b.remove("reasoningEffort");
        b.put("instructions", "x".repeat(20001));
        field(create(b), "instructions");
        b.remove("instructions");
        Map<String, Object> m = mcp("a", "https://a.example.com/x", "PROVIDER", "NONE", null);
        List<String> tools = new ArrayList<>();
        for (int i = 0; i < 51; i++) tools.add("t" + i);
        m.put("allowedTools", tools);
        b.put("mcpServers", List.of(m));
        field(create(b), "mcpServers.0.allowedTools");
        b.remove("mcpServers");
        b.put("model", "m".repeat(201));
        field(create(b), "model");
        b.put("model", "");
        field(create(b), "model");

        Map<String, Object> ok = profile("Edge", conn);
        ok.put("maxOutputTokens", 128000);
        ok.put("temperature", 2);
        ok.put("reasoningEffort", "minimal");
        ok.put("instructions", "x".repeat(20000));
        assertThat(create(ok).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void unknownConnectionIs400() {
        field(create(profile("P", "nope")), "connectionId");
        String conn = connection("Main", "openai");
        // a connection of another brand is not visible either
        org.bson.Document foreign = new org.bson.Document("workspaceId", other.getId()).append("name", "X")
                .append("provider", "openai");
        String fid = mongo.insert(foreign, "aiConnections").getObjectId("_id").toHexString();
        field(create(profile("P", fid)), "connectionId");
        assertThat(conn).isNotBlank();
    }

    @Test
    void duplicateNameIs409() {
        String conn = connection("Main", "openai");
        create(profile("P", conn));
        ResponseEntity<JsonNode> res = create(profile("P", conn));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().get("code").asString()).isEqualTo("DUPLICATE");
        assertThat(res.getBody().get("field").asString()).isEqualTo("name");
    }

    @Test
    void secretKeptWhenAbsentAndReplacedWhenSent() {
        String conn = connection("Main", "openai");
        Map<String, Object> b = profile("P", conn);
        b.put("mcpServers", List.of(mcp("crm", "https://a.example.com/x", "PROVIDER", "BEARER", SECRET)));
        JsonNode v = create(b).getBody();
        String id = v.get("id").asString();
        String serverId = v.get("mcpServers").get(0).get("id").asString();
        String hint = v.get("mcpServers").get(0).get("secretHint").asString();
        org.bson.Document before = mongo.findOne(new Query(), org.bson.Document.class, "aiProfiles");
        String cipher = ((org.bson.Document) ((List<?>) before.get("mcpServers")).get(0)).get("secret",
                org.bson.Document.class).getString("ciphertext");

        Map<String, Object> keep = profile("P2", conn);
        Map<String, Object> server = mcp("crm", "https://a.example.com/y", "PROVIDER", "BEARER", null);
        server.put("id", serverId);
        keep.put("mcpServers", List.of(server));
        keep.put("version", v.get("version").asLong());
        ResponseEntity<JsonNode> kept = ai(HttpMethod.PUT, "/profiles/" + id, manager, keep);
        assertThat(kept.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(kept.getBody().get("name").asString()).isEqualTo("P2");
        assertThat(kept.getBody().get("mcpServers").get(0).get("secretHint").asString()).isEqualTo(hint);
        assertThat(kept.getBody().get("mcpServers").get(0).get("id").asString()).isEqualTo(serverId);
        org.bson.Document mid = mongo.findOne(new Query(), org.bson.Document.class, "aiProfiles");
        assertThat(((org.bson.Document) ((List<?>) mid.get("mcpServers")).get(0)).get("secret", org.bson.Document.class)
                .getString("ciphertext")).isEqualTo(cipher);

        server.put("secret", "another-secret-0000");
        keep.put("version", kept.getBody().get("version").asLong());
        ResponseEntity<JsonNode> replaced = ai(HttpMethod.PUT, "/profiles/" + id, manager, keep);
        assertThat(replaced.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replaced.getBody().get("mcpServers").get(0).get("secretHint").asString()).isNotEqualTo(hint);
        org.bson.Document after = mongo.findOne(new Query(), org.bson.Document.class, "aiProfiles");
        assertThat(((org.bson.Document) ((List<?>) after.get("mcpServers")).get(0)).get("secret", org.bson.Document.class)
                .getString("ciphertext")).isNotEqualTo(cipher);
        assertThat(replaced.getBody().toString()).doesNotContain("another-secret-0000");

        // a stale version is refused
        ResponseEntity<JsonNode> stale = ai(HttpMethod.PUT, "/profiles/" + id, manager, keep);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // a server without an id is a new one, which needs its own secret
        Map<String, Object> fresh = mcp("other", "https://c.example.com/x", "PROVIDER", "BEARER", null);
        keep.put("mcpServers", List.of(server, fresh));
        keep.put("version", replaced.getBody().get("version").asLong());
        ResponseEntity<JsonNode> bad = ai(HttpMethod.PUT, "/profiles/" + id, manager, keep);
        assertThat(field(bad, "mcpServers.1.secret")).isNotBlank();
    }

    private JsonNode withServer() {
        String conn = connection("Main", "openai");
        Map<String, Object> b = profile("P", conn);
        Map<String, Object> m = mcp("crm", "https://a.example.com/x", "PROVIDER", "HEADER", SECRET);
        m.put("headerName", "X-Api-Key");
        b.put("mcpServers", List.of(m));
        return create(b).getBody();
    }

    private ResponseEntity<JsonNode> putServer(JsonNode v, Map<String, Object> change) {
        JsonNode s = v.get("mcpServers").get(0);
        Map<String, Object> server = mcp("crm", s.get("url").asString(), s.get("connectBy").asString(), "HEADER", null);
        server.put("id", s.get("id").asString());
        server.put("headerName", s.get("headerName").asString());
        server.putAll(change);
        Map<String, Object> b = profile("P", v.get("connectionId").asString());
        b.put("mcpServers", List.of(server));
        b.put("version", v.get("version").asLong());
        return ai(HttpMethod.PUT, "/profiles/" + v.get("id").asString(), manager, b);
    }

    @Test
    void keptSecretDoesNotFollowAChangedDestination() {
        JsonNode v = withServer();
        assertThat(field(putServer(v, Map.of("url", "https://attacker.example/")), "mcpServers.0.secret"))
                .isEqualTo("is required when the address changes");
        field(putServer(v, Map.of("url", "https://a.example.com:8443/x")), "mcpServers.0.secret");
        field(putServer(v, Map.of("url", "https://a.example.com/x", "connectBy", "APP")), "mcpServers.0.secret");
        field(putServer(v, Map.of("headerName", "X-Other")), "mcpServers.0.secret");
        org.bson.Document before = mongo.findOne(new Query(), org.bson.Document.class, "aiProfiles");
        String cipher = ((org.bson.Document) ((List<?>) before.get("mcpServers")).get(0)).get("secret",
                org.bson.Document.class).getString("ciphertext");

        assertThat(putServer(v, Map.of("url", "https://a.example.com/other")).getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode v2 = ai(HttpMethod.GET, "/profiles/" + v.get("id").asString(), manager, null).getBody();
        ResponseEntity<JsonNode> moved = putServer(v2, Map.of("url", "https://b.example.com/x", "secret", "fresh-secret-4321"));
        assertThat(moved.getStatusCode()).isEqualTo(HttpStatus.OK);
        org.bson.Document after = mongo.findOne(new Query(), org.bson.Document.class, "aiProfiles");
        assertThat(((org.bson.Document) ((List<?>) after.get("mcpServers")).get(0)).get("secret", org.bson.Document.class)
                .getString("ciphertext")).isNotEqualTo(cipher);
    }

    @Test
    void repeatedMcpServerIdIs400() {
        JsonNode v = withServer();
        JsonNode s = v.get("mcpServers").get(0);
        Map<String, Object> one = mcp("crm", "https://a.example.com/x", "PROVIDER", "NONE", null);
        one.put("id", s.get("id").asString());
        Map<String, Object> two = mcp("crm2", "https://a.example.com/x", "PROVIDER", "NONE", null);
        two.put("id", s.get("id").asString());
        Map<String, Object> b = profile("P", v.get("connectionId").asString());
        b.put("mcpServers", List.of(one, two));
        assertThat(field(ai(HttpMethod.PUT, "/profiles/" + v.get("id").asString(), manager, b), "mcpServers.1.id"))
                .isEqualTo("is used twice");
    }

    @Test
    void deleteUsedByDraftOrPublishedIs409() {
        String conn = connection("Main", "openai");
        String id = create(profile("P", conn)).getBody().get("id").asString();
        chatbotWithAiNode("Draft bot", id, true, false);
        chatbotWithAiNode("Published bot", id, false, true);
        chatbotWithAiNode("Unrelated", "someone-else", true, true);

        ResponseEntity<JsonNode> refused = ai(HttpMethod.DELETE, "/profiles/" + id, manager, null);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("ai_profile_in_use");
        assertThat(refused.getBody().get("message").asString()).isEqualTo("Used by chatbots: Draft bot, Published bot");

        mongo.remove(new Query(), "chatbots");
        mongo.remove(new Query(), "chatbotVersions");
        assertThat(ai(HttpMethod.DELETE, "/profiles/" + id, manager, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(ai(HttpMethod.GET, "/profiles/" + id, manager, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** A chatbot whose draft and newest version (2) are empty; only its old version 1 has the AI node. */
    private String chatbotWithOldAiVersion(String name, String profileId, String status) {
        Map<String, Object> flow = Map.of("nodes", List.of(
                Map.of("id", "n2", "type", "ai", "data", Map.of("name", "Ask", "settings", Map.of("profileId", profileId)))));
        Map<String, Object> empty = Map.of("nodes", List.of());
        org.bson.Document bot = new org.bson.Document("workspaceId", brand.getId()).append("name", name)
                .append("status", status).append("flow", empty).append("versionNumber", 2);
        String id = mongo.insert(bot, "chatbots").getObjectId("_id").toHexString();
        mongo.insert(new org.bson.Document("workspaceId", brand.getId()).append("chatbotId", id)
                .append("versionNumber", 1).append("flow", flow).append("savedAt", Instant.now()), "chatbotVersions");
        mongo.insert(new org.bson.Document("workspaceId", brand.getId()).append("chatbotId", id)
                .append("versionNumber", 2).append("flow", empty).append("savedAt", Instant.now()), "chatbotVersions");
        return id;
    }

    private org.bson.Document runOn(String chatbotId, int versionNumber, String status) {
        return mongo.insert(new org.bson.Document("workspaceId", brand.getId()).append("chatbotId", chatbotId)
                .append("versionNumber", versionNumber).append("status", status)
                .append("contactId", java.util.UUID.randomUUID().toString()), "chatbotRuns");
    }

    @Test
    void anOldVersionBlocksDeleteOnlyWhileARunUsesIt() {
        String conn = connection("Main", "openai");
        String id = create(profile("P", conn)).getBody().get("id").asString();
        String old = chatbotWithOldAiVersion("Moved on", id, "PUBLISHED");
        // a finished run on the old version does not count
        runOn(old, 1, "COMPLETED");
        runOn(old, 2, "WAITING");

        org.bson.Document active = runOn(old, 1, "WAITING");
        ResponseEntity<JsonNode> refused = ai(HttpMethod.DELETE, "/profiles/" + id, manager, null);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().get("message").asString()).isEqualTo("Used by chatbots: Moved on");

        mongo.remove(new Query(org.springframework.data.mongodb.core.query.Criteria.where("_id").is(active.get("_id"))),
                "chatbotRuns");
        runOn(old, 1, "RUNNING");
        assertThat(ai(HttpMethod.DELETE, "/profiles/" + id, manager, null).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        mongo.remove(new Query(), "chatbotRuns");
        // the old version was saved long ago and nothing runs on it: the profile can go
        assertThat(ai(HttpMethod.DELETE, "/profiles/" + id, manager, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void thePublishedVersionBlocksDelete() {
        String conn = connection("Main", "openai");
        String id = create(profile("P", conn)).getBody().get("id").asString();
        Map<String, Object> flow = Map.of("nodes", List.of(
                Map.of("id", "n2", "type", "ai", "data", Map.of("name", "Ask", "settings", Map.of("profileId", id)))));
        org.bson.Document bot = new org.bson.Document("workspaceId", brand.getId()).append("name", "Live")
                .append("status", "PUBLISHED").append("flow", Map.of("nodes", List.of())).append("versionNumber", 3);
        String botId = mongo.insert(bot, "chatbots").getObjectId("_id").toHexString();
        mongo.insert(new org.bson.Document("workspaceId", brand.getId()).append("chatbotId", botId)
                .append("versionNumber", 3).append("flow", flow).append("savedAt", Instant.now()), "chatbotVersions");

        ResponseEntity<JsonNode> refused = ai(HttpMethod.DELETE, "/profiles/" + id, manager, null);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().get("message").asString()).isEqualTo("Used by chatbots: Live");
    }

    @Test
    void chatbotOptionsListProfilesForChatbotEditors() {
        String conn = connection("Main", "openai");
        String z = create(profile("Zed", conn)).getBody().get("id").asString();
        String a = create(profile("Alpha", conn)).getBody().get("id").asString();
        User editor = roleUser("chatbot:view", "ChatView");

        ResponseEntity<JsonNode> res = rest.exchange(url("/api/workspaces/" + brand.getId() + "/chatbots/options"),
                HttpMethod.GET, auth(editor, null), JsonNode.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode list = res.getBody().get("aiProfiles");
        assertThat(list).hasSize(2);
        assertThat(list.get(0).get("id").asString()).isEqualTo(a);
        assertThat(list.get(0).get("name").asString()).isEqualTo("Alpha");
        assertThat(list.get(0).get("provider").asString()).isEqualTo("openai");
        assertThat(list.get(1).get("id").asString()).isEqualTo(z);
        assertThat(ai(HttpMethod.GET, "/profiles", editor, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void otherBrandIs404() {
        String conn = connection("Main", "openai");
        String id = create(profile("P", conn)).getBody().get("id").asString();

        ResponseEntity<JsonNode> cross = rest.exchange(url("/api/workspaces/" + other.getId() + "/ai/profiles/" + id),
                HttpMethod.GET, auth(admin, null), JsonNode.class);
        assertThat(cross.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.exchange(url("/api/workspaces/" + other.getId() + "/ai/profiles"), HttpMethod.GET,
                auth(manager, null), JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void viewerCanReadButNotWrite() {
        User viewer = roleUser("ai:view", "Viewer");
        String conn = connection("Main", "openai");
        create(profile("P", conn));
        assertThat(ai(HttpMethod.GET, "/profiles", viewer, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ai(HttpMethod.POST, "/profiles", viewer, profile("Q", conn)).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    private com.sun.net.httpserver.HttpServer mcpStub(java.util.concurrent.atomic.AtomicReference<String> keySeen, boolean fail)
            throws java.io.IOException {
        com.sun.net.httpserver.HttpServer srv = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/mcp", ex -> {
            try {
                JsonNode body = new tools.jackson.databind.json.JsonMapper().readTree(ex.getRequestBody().readAllBytes());
                keySeen.set(ex.getRequestHeaders().getFirst("X-Api-Key"));
                if (!body.has("id")) {
                    ex.sendResponseHeaders(202, -1);
                    return;
                }
                String method = body.get("method").asString();
                String result = fail ? null : "tools/list".equals(method)
                        ? "{\"tools\":[{\"name\":\"lookup\",\"description\":\"Find a customer\",\"inputSchema\":{\"type\":\"object\"}}]}"
                        : "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{}}";
                String msg = fail ? "{\"jsonrpc\":\"2.0\",\"id\":" + body.get("id") + ",\"error\":{\"code\":-1,\"message\":\"boom " + SECRET + "\"}}"
                        : "{\"jsonrpc\":\"2.0\",\"id\":" + body.get("id") + ",\"result\":" + result + "}";
                byte[] b = msg.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(200, b.length);
                ex.getResponseBody().write(b);
            } finally {
                ex.close();
            }
        });
        srv.start();
        return srv;
    }

    private JsonNode profileWithAppServer(String connectBy, String stubUrl) {
        return profileWithAppServer(connectBy, stubUrl, SECRET, "X-Api-Key");
    }

    private JsonNode profileWithAppServer(String connectBy, String stubUrl, String secret, String headerName) {
        String conn = connection("Main", "openai");
        Map<String, Object> b = profile("P", conn);
        Map<String, Object> m = mcp("crm", "https://a.example.com/mcp", connectBy, "HEADER", secret);
        m.put("headerName", headerName);
        b.put("mcpServers", List.of(m));
        JsonNode v = create(b).getBody();
        mongo.updateFirst(new Query(), new org.springframework.data.mongodb.core.query.Update().set("mcpServers.0.url", stubUrl),
                "aiProfiles");
        return v;
    }

    @Test
    void loadToolsForAppServer() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> key = new java.util.concurrent.atomic.AtomicReference<>();
        com.sun.net.httpserver.HttpServer srv = mcpStub(key, false);
        try {
            JsonNode v = profileWithAppServer("APP", "http://127.0.0.1:" + srv.getAddress().getPort() + "/mcp");
            String path = "/profiles/" + v.get("id").asString() + "/mcp/" + v.get("mcpServers").get(0).get("id").asString() + "/tools";
            ResponseEntity<JsonNode> res = ai(HttpMethod.POST, path, manager, null);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(res.getBody()).hasSize(1);
            assertThat(res.getBody().get(0).get("name").asString()).isEqualTo("lookup");
            assertThat(res.getBody().get(0).get("description").asString()).isEqualTo("Find a customer");
            assertThat(res.getBody().toString()).doesNotContain(SECRET);
            assertThat(key.get()).isEqualTo(SECRET);

            assertThat(ai(HttpMethod.POST, path, roleUser("ai:view", "Viewer"), null).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            ResponseEntity<JsonNode> unknown = ai(HttpMethod.POST,
                    "/profiles/" + v.get("id").asString() + "/mcp/nope/tools", manager, null);
            assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        } finally {
            srv.stop(0);
        }
    }

    @Test
    void pastedSecretIsStoredTrimmed() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> key = new java.util.concurrent.atomic.AtomicReference<>();
        com.sun.net.httpserver.HttpServer srv = mcpStub(key, false);
        try {
            // a secret pasted with a newline and a space, as copied from a terminal
            JsonNode v = profileWithAppServer("APP", "http://127.0.0.1:" + srv.getAddress().getPort() + "/mcp",
                    SECRET + "\n ", " X-Api-Key ");
            assertThat(v.get("mcpServers").get(0).get("headerName").asString()).isEqualTo("X-Api-Key");
            String path = "/profiles/" + v.get("id").asString() + "/mcp/" + v.get("mcpServers").get(0).get("id").asString() + "/tools";
            ResponseEntity<JsonNode> res = ai(HttpMethod.POST, path, manager, null);
            assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.OK);
            assertThat(key.get()).isEqualTo(SECRET);
        } finally {
            srv.stop(0);
        }
    }

    @Test
    void loadToolsRefusesProviderServer() throws Exception {
        JsonNode v = profileWithAppServer("PROVIDER", "https://a.example.com/mcp");
        ResponseEntity<JsonNode> res = ai(HttpMethod.POST, "/profiles/" + v.get("id").asString() + "/mcp/"
                + v.get("mcpServers").get(0).get("id").asString() + "/tools", manager, null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("code").asString()).isEqualTo("mcp_provider_server");
    }

    @Test
    void loadToolsFailureIs502WithoutSecret() throws Exception {
        com.sun.net.httpserver.HttpServer srv = mcpStub(new java.util.concurrent.atomic.AtomicReference<>(), true);
        try {
            JsonNode v = profileWithAppServer("APP", "http://127.0.0.1:" + srv.getAddress().getPort() + "/mcp");
            ResponseEntity<JsonNode> res = ai(HttpMethod.POST, "/profiles/" + v.get("id").asString() + "/mcp/"
                    + v.get("mcpServers").get(0).get("id").asString() + "/tools", manager, null);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(res.getBody().get("code").asString()).isEqualTo("mcp_error");
            assertThat(res.getBody().toString()).doesNotContain(SECRET).contains("boom");
        } finally {
            srv.stop(0);
        }
    }
}
