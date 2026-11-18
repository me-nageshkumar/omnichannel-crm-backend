package me.nageshk.omnichannel.app.chatbot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.db.access.Role;
import me.nageshk.omnichannel.db.access.RoleLevel;
import me.nageshk.omnichannel.db.access.RoleRepository;
import me.nageshk.omnichannel.db.chatbot.ChatbotSettingsDocument;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.db.user.WorkspaceRole;
import me.nageshk.omnichannel.security.access.BuiltInRoles;
import me.nageshk.omnichannel.security.access.RoleCache;
import me.nageshk.omnichannel.service.chatbot.settings.ChatbotSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

class ChatbotSettingsApiIT extends ChatbotTestBase {

    @Autowired RoleRepository roles;
    @Autowired RoleCache roleCache;
    @Autowired ChatbotSettingsService settingsService;

    private User root;

    @Override
    protected List<Class<?>> collections() {
        List<Class<?>> c = new ArrayList<>(super.collections());
        c.add(ChatbotSettingsDocument.class);
        return c;
    }

    @BeforeEach
    void superAdmin() {
        root = testUsers.create("root", "pw", BuiltInRoles.SUPER_ADMIN, null);
    }

    private ResponseEntity<JsonNode> platform(HttpMethod m, User who, Object body) {
        return rest.exchange(url("/api/platform/chatbot-settings"), m, auth(who, body), JsonNode.class);
    }

    private ResponseEntity<JsonNode> brandSettings(HttpMethod m, String workspaceId, User who, Object body) {
        return rest.exchange(url("/api/workspaces/" + workspaceId + "/chatbots/settings"), m, auth(who, body),
                JsonNode.class);
    }

    private static Map<String, Object> body(String key, Map<String, Object> layer, JsonNode version) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put(key, layer);
        b.put("version", version == null || version.isNull() ? null : version.asLong());
        return b;
    }

    private Map<String, Object> platformLayer(Map<String, Object> changes) {
        JsonNode current = platform(HttpMethod.GET, root, null).getBody().get("settings");
        @SuppressWarnings("unchecked")
        Map<String, Object> s = new LinkedHashMap<>(mapper.convertValue(current, Map.class));
        s.putAll(changes);
        return s;
    }

    private ResponseEntity<JsonNode> savePlatform(Map<String, Object> changes) {
        JsonNode version = platform(HttpMethod.GET, root, null).getBody().get("version");
        return platform(HttpMethod.PUT, root, body("settings", platformLayer(changes), version));
    }

    private ResponseEntity<JsonNode> saveBrand(String workspaceId, User who, Map<String, Object> own) {
        JsonNode version = brandSettings(HttpMethod.GET, workspaceId, manager, null).getBody().get("version");
        return brandSettings(HttpMethod.PUT, workspaceId, who, body("own", own, version));
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
    void platformStartsWithCodeDefaults() {
        ResponseEntity<JsonNode> res = platform(HttpMethod.GET, root, null);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode s = res.getBody().get("settings");
        assertThat(s.get("endAfterMinutes").asInt()).isEqualTo(120);
        assertThat(s.get("reminderOn").asBoolean()).isFalse();
        assertThat(s.get("reminderAfterMinutes").asInt()).isEqualTo(30);
        assertThat(s.get("endMessage").asString()).isEqualTo("");
        assertThat(res.getBody().get("version").isNull()).isTrue();
    }

    @Test
    void newFieldsDefaultToCloseAndRoute() {
        JsonNode s = platform(HttpMethod.GET, root, null).getBody().get("settings");
        assertThat(s.get("whenCompleted").asString()).isEqualTo("CLOSE");
        assertThat(s.get("whenAbandoned").asString()).isEqualTo("ROUTE");
        assertThat(settingsService.resolve(brand.getId(), null).whenCompleted())
                .isEqualTo(me.nageshk.omnichannel.db.chatbot.AfterChatbot.CLOSE);
        assertThat(settingsService.resolve(brand.getId(), null).whenAbandoned())
                .isEqualTo(me.nageshk.omnichannel.db.chatbot.AfterChatbot.ROUTE);
    }

    @Test
    void brandLayerOverridesWhenAbandoned() {
        assertThat(saveBrand(brand.getId(), manager, Map.of("whenAbandoned", "CLOSE")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(settingsService.resolve(brand.getId(), null).whenAbandoned())
                .isEqualTo(me.nageshk.omnichannel.db.chatbot.AfterChatbot.CLOSE);
        Map<String, Object> own = new LinkedHashMap<>();
        own.put("whenAbandoned", "ROUTE");
        me.nageshk.omnichannel.db.chatbot.ChatbotSettingsLayer chatbotLayer = mapper.convertValue(own,
                me.nageshk.omnichannel.db.chatbot.ChatbotSettingsLayer.class);
        assertThat(settingsService.resolve(brand.getId(), chatbotLayer).whenAbandoned())
                .isEqualTo(me.nageshk.omnichannel.db.chatbot.AfterChatbot.ROUTE);
    }

    @Test
    void storedLayerWithoutNewFieldsInherits() {
        assertThat(saveBrand(brand.getId(), manager, Map.of("retryMessage", "Brand retry")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        // a brand layer saved before the fields existed has no keys for them
        mongo.getCollection(mongo.getCollectionName(ChatbotSettingsDocument.class)).updateMany(new org.bson.Document(),
                new org.bson.Document("$unset", new org.bson.Document("layer.whenCompleted", "").append("layer.whenAbandoned", "")
                        .append("settings.whenCompleted", "").append("settings.whenAbandoned", "")));
        me.nageshk.omnichannel.db.chatbot.ChatbotSettings s = settingsService.resolve(brand.getId(), null);
        assertThat(s.retryMessage()).isEqualTo("Brand retry");
        assertThat(s.whenCompleted()).isEqualTo(me.nageshk.omnichannel.db.chatbot.AfterChatbot.CLOSE);
        assertThat(s.whenAbandoned()).isEqualTo(me.nageshk.omnichannel.db.chatbot.AfterChatbot.ROUTE);
    }

    @Test
    void platformSaveIsStoredAndValidated() {
        assertThat(savePlatform(Map.of("endAfterMinutes", 90)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(platform(HttpMethod.GET, root, null).getBody().at("/settings/endAfterMinutes").asInt()).isEqualTo(90);

        ResponseEntity<JsonNode> bad = savePlatform(Map.of("retryMessage", ""));
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bad.getBody().toString()).contains("settings.retryMessage");
    }

    @Test
    void platformNeedsTheDefaultsRight() {
        assertThat(platform(HttpMethod.GET, manager, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(platform(HttpMethod.GET, staff, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(platform(HttpMethod.PUT, staff, body("settings", platformLayer(Map.of()), null)).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void brandStartsEmptyAndInheritsThePlatform() {
        ResponseEntity<JsonNode> res = brandSettings(HttpMethod.GET, brand.getId(), manager, null);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        res.getBody().get("own").properties().forEach(e -> assertThat(e.getValue().isNull()).as(e.getKey()).isTrue());
        assertThat(res.getBody().get("own").size()).isEqualTo(11);
        assertThat(res.getBody().at("/inherited/endAfterMinutes").asInt()).isEqualTo(120);
    }

    @Test
    void brandSeesPlatformChangeInInherited() {
        savePlatform(Map.of("endAfterMinutes", 90));

        assertThat(brandSettings(HttpMethod.GET, brand.getId(), manager, null).getBody().at("/inherited/endAfterMinutes")
                .asInt()).isEqualTo(90);
    }

    @Test
    void emptyBrandLayerFollowsThePlatform() {
        assertThat(saveBrand(brand.getId(), manager, Map.of()).getStatusCode()).isEqualTo(HttpStatus.OK);
        savePlatform(Map.of("retryMessage", "Platform retry"));

        assertThat(settingsService.inheritedFor(brand.getId()).retryMessage()).isEqualTo("Platform retry");
    }

    @Test
    void brandLayerWinsOverThePlatform() {
        saveBrand(brand.getId(), manager, Map.of("retryMessage", "Brand retry"));
        savePlatform(Map.of("retryMessage", "Platform retry", "endAfterMinutes", 60));

        JsonNode res = brandSettings(HttpMethod.GET, brand.getId(), manager, null).getBody();
        assertThat(res.at("/own/retryMessage").asString()).isEqualTo("Brand retry");
        assertThat(settingsService.inheritedFor(brand.getId()).retryMessage()).isEqualTo("Brand retry");
        assertThat(settingsService.inheritedFor(brand.getId()).endAfterMinutes()).isEqualTo(60);
    }

    @Test
    void brandPutNeedsManage() {
        User viewer = roleUser("chatbot:view", "CHATBOT_VIEWER_");

        assertThat(brandSettings(HttpMethod.GET, brand.getId(), viewer, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(saveBrand(brand.getId(), viewer, Map.of("endAfterMinutes", 60)).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void brandSettingsAreScopedToTheWorkspace() {
        User otherManager = brandUser(other, "otherManager", BuiltInRoles.WORKSPACE_MANAGER);

        assertThat(brandSettings(HttpMethod.GET, brand.getId(), otherManager, null).getStatusCode().is4xxClientError())
                .isTrue();
        saveBrand(brand.getId(), manager, Map.of("endAfterMinutes", 60));
        assertThat(brandSettings(HttpMethod.GET, other.getId(), otherManager, null).getBody().at("/own/endAfterMinutes")
                .isNull()).isTrue();
    }

    @Test
    void staleSaveIs409() {
        JsonNode bv = brandSettings(HttpMethod.GET, brand.getId(), manager, null).getBody().get("version");
        assertThat(brandSettings(HttpMethod.PUT, brand.getId(), manager, body("own", Map.of("endAfterMinutes", 60), bv))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(brandSettings(HttpMethod.PUT, brand.getId(), manager, body("own", Map.of("endAfterMinutes", 70), bv))
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        JsonNode pv = platform(HttpMethod.GET, root, null).getBody().get("version");
        Map<String, Object> layer = platformLayer(Map.of());
        assertThat(platform(HttpMethod.PUT, root, body("settings", layer, pv)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(platform(HttpMethod.PUT, root, body("settings", layer, pv)).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void brandRejectsBadValuesByField() {
        ResponseEntity<JsonNode> res = saveBrand(brand.getId(), manager, Map.of("invalidReplyRetries", 9));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().toString()).contains("own.invalidReplyRetries");
    }
}
