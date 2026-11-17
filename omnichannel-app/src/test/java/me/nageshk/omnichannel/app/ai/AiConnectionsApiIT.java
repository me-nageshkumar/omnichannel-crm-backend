package me.nageshk.omnichannel.app.ai;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.ai.connection.AiConnection;
import me.nageshk.omnichannel.db.access.Role;
import me.nageshk.omnichannel.db.access.RoleLevel;
import me.nageshk.omnichannel.db.access.RoleRepository;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.db.user.WorkspaceRole;
import me.nageshk.omnichannel.security.access.RoleCache;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

class AiConnectionsApiIT extends AiTestBase {

    private static final String KEY = "sk-test-1234567890a91F";

    @Autowired RoleRepository roles;
    @Autowired RoleCache roleCache;

    private static Map<String, Object> save(String name, String provider, String apiKey) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("name", name);
        b.put("provider", provider);
        if (apiKey != null) b.put("apiKey", apiKey);
        return b;
    }

    private ResponseEntity<JsonNode> create(String name) {
        return ai(HttpMethod.POST, "/connections", manager, save(name, "openai", KEY));
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

    @Test
    void createVerifiesAndShowsOnlyAHint() {
        ResponseEntity<JsonNode> res = create("Main");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode v = res.getBody();
        assertThat(v.get("keyHint").asString()).isEqualTo("sk-…a91F");
        assertThat(v.get("status").asString()).isEqualTo("OK");
        assertThat(v.get("keySetAt").isNull()).isFalse();
        assertThat(v.get("checkedAt").isNull()).isFalse();
        assertThat(v.get("version").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(res.getBody().toString()).doesNotContain(KEY);
        assertThat(OPENAI.last().authorization()).isEqualTo("Bearer " + KEY);
        assertThat(ai(HttpMethod.GET, "/connections", manager, null).getBody().toString()).doesNotContain(KEY);

        org.bson.Document stored = mongo.findOne(new Query(), org.bson.Document.class, "aiConnections");
        assertThat(stored.toJson()).doesNotContain(KEY);
        assertThat(stored.get("apiKey", org.bson.Document.class).getString("ciphertext")).isNotBlank();
        assertThat(stored.getString("workspaceId")).isEqualTo(brand.getId());
        assertThat(stored.getString("customerId")).isEqualTo(acme.getId());
    }

    @Test
    void pastedKeyIsStoredTrimmed() {
        ResponseEntity<JsonNode> res = ai(HttpMethod.POST, "/connections", manager, save("Main", "openai", " " + KEY + "\n "));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody().get("status").asString()).isEqualTo("OK");
        assertThat(res.getBody().get("keyHint").asString()).isEqualTo("sk-…a91F");
        assertThat(OPENAI.last().authorization()).isEqualTo("Bearer " + KEY);
        // the stored key, opened again for a later check, is the trimmed one
        OPENAI.reset();
        ai(HttpMethod.POST, "/connections/" + res.getBody().get("id").asString() + "/verify", manager, null);
        assertThat(OPENAI.last().authorization()).isEqualTo("Bearer " + KEY);
    }

    @Test
    void putWithoutKeyKeepsIt() {
        JsonNode created = create("Main").getBody();
        OPENAI.reset();
        Map<String, Object> body = save("Renamed", "openai", null);
        body.put("version", created.get("version").asLong());

        ResponseEntity<JsonNode> res = ai(HttpMethod.PUT, "/connections/" + created.get("id").asString(), manager, body);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("name").asString()).isEqualTo("Renamed");
        assertThat(res.getBody().get("keyHint").asString()).isEqualTo("sk-…a91F");
        assertThat(res.getBody().get("keySetAt")).isEqualTo(created.get("keySetAt"));
        assertThat(OPENAI.requests()).isEmpty();

        ResponseEntity<JsonNode> stale = ai(HttpMethod.PUT, "/connections/" + created.get("id").asString(), manager, body);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void putWithKeyReplacesIt() {
        JsonNode created = create("Main").getBody();
        OPENAI.reset();
        clock.set(NOW.plusSeconds(60));
        String fresh = "sk-new-key-0000000000zzZ9";

        ResponseEntity<JsonNode> res = ai(HttpMethod.PUT, "/connections/" + created.get("id").asString(), manager,
                save("Main", "openai", fresh));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("keyHint").asString()).isEqualTo("sk-…zzZ9");
        assertThat(res.getBody().get("keySetAt")).isNotEqualTo(created.get("keySetAt"));
        assertThat(OPENAI.last().authorization()).isEqualTo("Bearer " + fresh);
        assertThat(res.getBody().toString()).doesNotContain(fresh);
    }

    @Test
    void badKeyIsInvalid() {
        OPENAI.enqueue(401, "{\"error\":{\"message\":\"Incorrect API key provided: " + KEY + "\"}}");

        ResponseEntity<JsonNode> res = create("Main");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody().get("status").asString()).isEqualTo("INVALID");
        assertThat(res.getBody().get("lastError").asString()).isEqualTo("The provider refused the key");
        assertThat(res.getBody().toString()).doesNotContain(KEY);
    }

    @Test
    void providerOutageKeepsTheConnectionUnverified() {
        OPENAI.enqueue(500, "{\"error\":{\"message\":\"boom\"}}");

        JsonNode v = create("Main").getBody();

        assertThat(v.get("status").asString()).isEqualTo("UNVERIFIED");
        assertThat(v.get("lastError").asString()).isNotBlank();
    }

    @Test
    void verifyEndpointRechecks() {
        JsonNode created = create("Main").getBody();
        OPENAI.enqueue(401, "{}");

        ResponseEntity<JsonNode> res = ai(HttpMethod.POST, "/connections/" + created.get("id").asString() + "/verify",
                manager, null);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("status").asString()).isEqualTo("INVALID");
    }

    @Test
    void settingsOnlySaveWithAnUnopenableKeyIsSavedUnverified() {
        JsonNode created = create("Main").getBody();
        mongo.updateFirst(new Query(Criteria.where("name").is("Main")),
                new org.springframework.data.mongodb.core.query.Update().set("apiKey.keyId", "gone"), "aiConnections");
        OPENAI.reset();

        ResponseEntity<JsonNode> res = ai(HttpMethod.PUT, "/connections/" + created.get("id").asString(), manager,
                withSettings(save("Main", "openai", null), "project", "proj_9"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("settings").get("project").asString()).isEqualTo("proj_9");
        assertThat(res.getBody().get("status").asString()).isEqualTo("UNVERIFIED");
        assertThat(res.getBody().get("lastError").asString()).isEqualTo("The stored key cannot be opened; enter it again");
        assertThat(OPENAI.requests()).isEmpty();
    }

    @Test
    void unknownProviderIs400() {
        ResponseEntity<JsonNode> res = ai(HttpMethod.POST, "/connections", manager, save("Main", "nope", KEY));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().toString()).contains("provider");
    }

    @Test
    void duplicateNameIs409() {
        create("Main");

        ResponseEntity<JsonNode> res = create("Main");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().get("code").asString()).isEqualTo("DUPLICATE");
    }

    @Test
    void createWithoutKeyIs400() {
        ResponseEntity<JsonNode> res = ai(HttpMethod.POST, "/connections", manager, save("Main", "openai", null));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().toString()).contains("apiKey");
    }

    @Test
    void modelsAndVectorStoresListed() {
        String id = create("Main").getBody().get("id").asString();
        OPENAI.enqueue(200, "{\"data\":[{\"id\":\"gpt-5\"},{\"id\":\"gpt-4o\"}]}");
        OPENAI.enqueue(200, "{\"data\":[{\"id\":\"vs_1\",\"name\":\"Docs\",\"file_counts\":{\"completed\":7}}]}");

        JsonNode models = ai(HttpMethod.GET, "/connections/" + id + "/models", manager, null).getBody();
        JsonNode stores = ai(HttpMethod.GET, "/connections/" + id + "/vector-stores", manager, null).getBody();

        assertThat(models.get(0).get("id").asString()).isEqualTo("gpt-5");
        assertThat(models.size()).isEqualTo(2);
        assertThat(stores.get(0).get("id").asString()).isEqualTo("vs_1");
        assertThat(stores.get(0).get("name").asString()).isEqualTo("Docs");
        assertThat(stores.get(0).get("fileCount").asLong()).isEqualTo(7);
    }

    @Test
    void otherBrandIs404() {
        String id = create("Main").getBody().get("id").asString();

        ResponseEntity<JsonNode> res = rest.exchange(
                url("/api/workspaces/" + other.getId() + "/ai/connections"), HttpMethod.GET, auth(manager, null),
                JsonNode.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // an admin of the whole company reaches the other brand, but not a connection of this one
        ResponseEntity<JsonNode> cross = rest.exchange(
                url("/api/workspaces/" + other.getId() + "/ai/connections/" + id), HttpMethod.GET, auth(admin, null),
                JsonNode.class);
        assertThat(cross.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void agentWithoutAiViewIs403() {
        assertThat(ai(HttpMethod.GET, "/connections", agent, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ai(HttpMethod.POST, "/connections", agent, save("Main", "openai", KEY)).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ai(HttpMethod.GET, "/providers", agent, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void viewerCanReadButNotWrite() {
        User viewer = roleUser("ai:view", "Viewer");
        create("Main");

        assertThat(ai(HttpMethod.GET, "/connections", viewer, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ai(HttpMethod.POST, "/connections", viewer, save("Two", "openai", KEY)).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void chatbotEditorCanListProviders() {
        User editor = roleUser("chatbot:manage", "Editor");

        ResponseEntity<JsonNode> res = ai(HttpMethod.GET, "/providers", editor, null);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode openai = null;
        for (JsonNode p : res.getBody()) if ("openai".equals(p.get("id").asString())) openai = p;
        assertThat(openai).isNotNull();
        assertThat(openai.get("label").asString()).isEqualTo("OpenAI");
        assertThat(openai.get("capabilities").get("vectorStores").asBoolean()).isTrue();
        assertThat(ai(HttpMethod.GET, "/connections", editor, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void scriptedProviderIsListedAndVerifies() {
        ResponseEntity<JsonNode> res = ai(HttpMethod.POST, "/connections", manager, save("Fake", "scripted", "good"));
        assertThat(res.getBody().get("status").asString()).isEqualTo("OK");
        ResponseEntity<JsonNode> bad = ai(HttpMethod.POST, "/connections", manager, save("Fake2", "scripted", "bad"));
        assertThat(bad.getBody().get("status").asString()).isEqualTo("INVALID");
    }

    @Test
    void deleteRemovesAndRefusesWhileAProfileUsesIt() {
        String id = create("Main").getBody().get("id").asString();
        mongo.insert(new org.bson.Document("workspaceId", brand.getId()).append("name", "Support bot")
                .append("connectionId", id), "aiProfiles");

        ResponseEntity<JsonNode> refused = ai(HttpMethod.DELETE, "/connections/" + id, manager, null);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("ai_connection_in_use");
        assertThat(refused.getBody().get("message").asString()).isEqualTo("Used by profiles: Support bot");

        mongo.remove(new Query(), "aiProfiles");
        assertThat(ai(HttpMethod.DELETE, "/connections/" + id, manager, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(ai(HttpMethod.GET, "/connections/" + id, manager, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(mongo.count(new Query(Criteria.where("workspaceId").is(brand.getId())), AiConnection.class))
                .isZero();
    }

    @Test
    void keyNeverLogged() {
        Logger root = (Logger) LoggerFactory.getLogger("me.nageshk.omnichannel");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            String id = create("Main").getBody().get("id").asString();
            OPENAI.enqueue(401, "{\"error\":{\"message\":\"Incorrect API key provided: " + KEY + "\"}}");
            ai(HttpMethod.POST, "/connections/" + id + "/verify", manager, null);
            OPENAI.enqueue(401, "{\"error\":{\"message\":\"Incorrect API key provided: " + KEY + "\"}}");
            ai(HttpMethod.GET, "/connections/" + id + "/models", manager, null);
        } finally {
            root.detachAppender(appender);
        }
        for (ILoggingEvent e : appender.list) {
            assertThat(e.getFormattedMessage()).doesNotContain(KEY);
            if (e.getThrowableProxy() != null) {
                assertThat(ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(e.getThrowableProxy()))
                        .doesNotContain(KEY);
            }
        }
    }

    private static Map<String, Object> withSettings(Map<String, Object> body, String... kv) {
        Map<String, String> settings = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) settings.put(kv[i], kv[i + 1]);
        body.put("settings", settings);
        return body;
    }

    @Test
    void settingsAreStoredReturnedAndSent() {
        ResponseEntity<JsonNode> res = ai(HttpMethod.POST, "/connections", manager,
                withSettings(save("Main", "openai", KEY), "organization", " org_1 "));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody().get("settings").toString()).isEqualTo("{\"organization\":\"org_1\"}");
        assertThat(OPENAI.last().organization()).isEqualTo("org_1");
        assertThat(OPENAI.last().project()).isNull();
        JsonNode read = ai(HttpMethod.GET, "/connections/" + res.getBody().get("id").asString(), manager, null).getBody();
        assertThat(read.get("settings").get("organization").asString()).isEqualTo("org_1");

        // the lists the profile form loads carry the settings too
        OPENAI.reset();
        ai(HttpMethod.GET, "/connections/" + res.getBody().get("id").asString() + "/models", manager, null);
        assertThat(OPENAI.last().organization()).isEqualTo("org_1");
    }

    @Test
    void settingsOnlyChangeVerifiesAgain() {
        JsonNode created = create("Main").getBody();
        OPENAI.reset();
        Map<String, Object> body = withSettings(save("Main", "openai", null), "project", "proj_9");
        body.put("version", created.get("version").asLong());

        ResponseEntity<JsonNode> res = ai(HttpMethod.PUT, "/connections/" + created.get("id").asString(), manager, body);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(OPENAI.requests()).hasSize(1);
        assertThat(OPENAI.last().project()).isEqualTo("proj_9");
        assertThat(OPENAI.last().authorization()).isEqualTo("Bearer " + KEY);
        assertThat(res.getBody().get("keyHint").asString()).isEqualTo("sk-…a91F");
        assertThat(res.getBody().get("keySetAt")).isEqualTo(created.get("keySetAt"));
        assertThat(res.getBody().get("status").asString()).isEqualTo("OK");
    }

    @Test
    void settingsThatTheProviderRefusesShowOnTheConnection() {
        JsonNode created = create("Main").getBody();
        OPENAI.enqueue(401, "{\"error\":{\"message\":\"No such organization\"}}");

        ResponseEntity<JsonNode> res = ai(HttpMethod.PUT, "/connections/" + created.get("id").asString(), manager,
                withSettings(save("Main", "openai", null), "organization", "org_wrong"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("status").asString()).isEqualTo("INVALID");
        // not "refused the key": the key was fine, and the provider's own words say what was not
        assertThat(res.getBody().get("lastError").asString())
                .startsWith("The provider refused the key or the connection's settings: ")
                .contains("No such organization");
    }

    @Test
    void unchangedSaveMakesNoProviderCall() {
        JsonNode created = ai(HttpMethod.POST, "/connections", manager,
                withSettings(save("Main", "openai", KEY), "organization", "org_1")).getBody();
        OPENAI.reset();

        ResponseEntity<JsonNode> res = ai(HttpMethod.PUT, "/connections/" + created.get("id").asString(), manager,
                withSettings(save("Main", "openai", null), "organization", "org_1"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(OPENAI.requests()).isEmpty();
    }

    @Test
    void saveWithoutSettingsKeepsThem() {
        JsonNode created = ai(HttpMethod.POST, "/connections", manager,
                withSettings(save("Main", "openai", KEY), "organization", "org_1")).getBody();
        OPENAI.reset();

        ResponseEntity<JsonNode> res = ai(HttpMethod.PUT, "/connections/" + created.get("id").asString(), manager,
                save("Renamed", "openai", null));

        assertThat(res.getBody().get("settings").get("organization").asString()).isEqualTo("org_1");
        assertThat(OPENAI.requests()).isEmpty();
    }

    @Test
    void emptySettingsClearThem() {
        JsonNode created = ai(HttpMethod.POST, "/connections", manager,
                withSettings(save("Main", "openai", KEY), "organization", "org_1")).getBody();
        OPENAI.reset();

        ResponseEntity<JsonNode> res = ai(HttpMethod.PUT, "/connections/" + created.get("id").asString(), manager,
                withSettings(save("Main", "openai", null)));

        assertThat(res.getBody().get("settings").size()).isZero();
        assertThat(OPENAI.last().organization()).isNull();
    }

    @Test
    void unknownSettingIs400() {
        ResponseEntity<JsonNode> res = ai(HttpMethod.POST, "/connections", manager,
                withSettings(save("Main", "openai", KEY), "baseUrl", "https://x.example"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().toString()).contains("settings.baseUrl");
        assertThat(OPENAI.requests()).isEmpty();
    }

    @Test
    void settingWithALineBreakIs400() {
        ResponseEntity<JsonNode> res = ai(HttpMethod.POST, "/connections", manager,
                withSettings(save("Main", "openai", KEY), "organization", "org_1\r\nX-Evil: 1"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().toString()).contains("settings.organization");
    }

    @Test
    void connectionWithoutSettingsFieldStillWorks() {
        JsonNode created = create("Main").getBody();
        mongo.updateFirst(new Query(Criteria.where("name").is("Main")),
                new org.springframework.data.mongodb.core.query.Update().unset("settings"), "aiConnections");
        assertThat(mongo.findOne(new Query(), org.bson.Document.class, "aiConnections").containsKey("settings")).isFalse();

        JsonNode read = ai(HttpMethod.GET, "/connections/" + created.get("id").asString(), manager, null).getBody();
        assertThat(read.get("settings").isObject()).isTrue();
        assertThat(read.get("settings").size()).isZero();
        ResponseEntity<JsonNode> verified = ai(HttpMethod.POST, "/connections/" + created.get("id").asString() + "/verify",
                manager, null);
        assertThat(verified.getBody().get("status").asString()).isEqualTo("OK");
    }

    @Test
    void providerChangeDropsTheOldProvidersSettings() {
        JsonNode created = ai(HttpMethod.POST, "/connections", manager,
                withSettings(save("Main", "openai", KEY), "organization", "org_1")).getBody();

        ResponseEntity<JsonNode> res = ai(HttpMethod.PUT, "/connections/" + created.get("id").asString(), manager,
                withSettings(save("Main", "scripted", "good"), "organization", "org_1"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("provider").asString()).isEqualTo("scripted");
        assertThat(res.getBody().get("settings").size()).isZero();
    }

    @Test
    void providersListCarriesConnectionFields() {
        JsonNode openai = null;
        JsonNode scripted = null;
        for (JsonNode p : ai(HttpMethod.GET, "/providers", manager, null).getBody()) {
            if ("openai".equals(p.get("id").asString())) openai = p;
            if ("scripted".equals(p.get("id").asString())) scripted = p;
        }

        assertThat(openai.get("connectionFields")).hasSize(2);
        assertThat(openai.get("connectionFields").get(0).get("key").asString()).isEqualTo("organization");
        assertThat(openai.get("connectionFields").get(0).get("kind").asString()).isEqualTo("TEXT");
        assertThat(openai.get("connectionFields").get(0).get("required").asBoolean()).isFalse();
        assertThat(openai.get("connectionFields").get(1).get("key").asString()).isEqualTo("project");
        assertThat(scripted.get("connectionFields")).isEmpty();
    }

    @Test
    void keyPastedIntoASettingIs400() {
        ResponseEntity<JsonNode> res = ai(HttpMethod.POST, "/connections", manager,
                withSettings(save("Main", "openai", KEY), "organization", KEY));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("fields").get("settings.organization").asString())
                .isEqualTo("is the API key; settings are not secret, so the key goes in API key only");
        assertThat(mongo.count(new Query(), "aiConnections")).isZero();
    }

    @Test
    void storedKeyPastedIntoASettingOnEditIs400() {
        JsonNode created = create("Main").getBody();
        OPENAI.reset();

        ResponseEntity<JsonNode> res = ai(HttpMethod.PUT, "/connections/" + created.get("id").asString(), manager,
                withSettings(save("Main", "openai", null), "project", " " + KEY + " "));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("fields").has("settings.project")).isTrue();
        assertThat(mongo.findOne(new Query(), org.bson.Document.class, "aiConnections").toJson()).doesNotContain(KEY);
        assertThat(OPENAI.requests()).isEmpty();
    }

    @Test
    void renameWithoutSettingsDropsStoredOnesTheProviderNoLongerDeclares() {
        JsonNode created = ai(HttpMethod.POST, "/connections", manager,
                withSettings(save("Main", "openai", KEY), "organization", "org_1")).getBody();
        mongo.updateFirst(new Query(Criteria.where("name").is("Main")),
                new org.springframework.data.mongodb.core.query.Update().set("settings.legacy", "x"), "aiConnections");

        ResponseEntity<JsonNode> res = ai(HttpMethod.PUT, "/connections/" + created.get("id").asString(), manager,
                save("Renamed", "openai", null));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("settings").toString()).isEqualTo("{\"organization\":\"org_1\"}");
    }
}
