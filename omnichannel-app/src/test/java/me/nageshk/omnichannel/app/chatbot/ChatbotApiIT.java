package me.nageshk.omnichannel.app.chatbot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.db.chatbot.ChatbotVersion;
import me.nageshk.omnichannel.db.chatbot.ChatbotVersionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** Spec §4.1, §6, §8.1: the chatbots API keeps the flow verbatim, reports problems, and gates publishing. */
class ChatbotApiIT extends ChatbotTestBase {

    @Autowired ChatbotVersionRepository versions;

    private static Map<String, Object> buttonsMessage() {
        return Map.of("message", Map.of("type", "interactive", "interactive", Map.of("type", "button",
                "body", Map.of("text", "Book a demo?"), "action", Map.of("buttons", List.of(
                        Map.of("type", "reply", "reply", Map.of("id", "yes", "title", "Yes")),
                        Map.of("type", "reply", "reply", Map.of("id", "no", "title", "No")))))));
    }

    private Map<String, Object> save(JsonNode bot, Map<String, Object> flow, Long version) {
        Map<String, Object> save = new LinkedHashMap<>();
        save.put("flow", flow);
        save.put("settings", mapper.convertValue(bot.get("settings"), Map.class));
        save.put("version", version);
        return save;
    }

    @Test
    void createListGetDelete() {
        ResponseEntity<JsonNode> created = bots(HttpMethod.POST, "", manager, Map.of("name", "Lead qualifier"));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode bot = created.getBody();
        assertThat(bot.get("status").asString()).isEqualTo("DRAFT");
        assertThat(bot.get("settings").get("endAfterMinutes").asInt()).isEqualTo(120);
        assertThat(bot.get("settings").get("invalidReplyRetries").asInt()).isEqualTo(2);
        assertThat(bot.get("flow").get("nodes")).isEmpty();
        ResponseEntity<JsonNode> list = bots(HttpMethod.GET, "", manager, null);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody()).hasSize(1);
        assertThat(list.getBody().get(0).get("name").asString()).isEqualTo("Lead qualifier");
        assertThat(list.getBody().get(0).get("runs7d").asInt()).isZero();
        String id = bot.get("id").asString();
        assertThat(bots(HttpMethod.GET, "/" + id, manager, null).getBody().get("name").asString()).isEqualTo("Lead qualifier");
        assertThat(bots(HttpMethod.DELETE, "/" + id, manager, null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(bots(HttpMethod.GET, "/" + id, manager, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void saveKeepsFlowVerbatimAndReturnsProblems() {
        JsonNode created = bots(HttpMethod.POST, "", manager, Map.of("name", "Bot")).getBody();
        Map<String, Object> flow = flow(List.of(node("n1", "send_message", "Ask", buttonsMessage())), List.of());
        ResponseEntity<JsonNode> saved = bots(HttpMethod.PUT, "/" + created.get("id").asString(), manager,
                save(created, flow, created.get("version").asLong()));
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode problems = saved.getBody().get("problems");
        assertThat(problems).hasSize(1);
        assertThat(problems.get(0).get("severity").asString()).isEqualTo("WARNING");
        assertThat(problems.get(0).get("nodeId").asString()).isEqualTo("n1");
        JsonNode got = bots(HttpMethod.GET, "/" + created.get("id").asString(), manager, null).getBody();
        assertThat(got.get("flow").get("nodes").get(0).get("position").get("x").asInt()).isEqualTo(100);
        assertThat(got.get("flow").get("viewport").get("zoom").asInt()).isEqualTo(1);
        assertThat(got.get("versionNumber").asInt()).isEqualTo(1);
    }

    @Test
    void saveRefusesUnreadableFlow() {
        JsonNode created = bots(HttpMethod.POST, "", manager, Map.of("name", "Bot")).getBody();
        Map<String, Object> flow = flow(List.of(node("n1", "open_ai", "Ai", Map.of())), List.of());
        ResponseEntity<JsonNode> saved = bots(HttpMethod.PUT, "/" + created.get("id").asString(), manager,
                save(created, flow, created.get("version").asLong()));
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(saved.getBody().get("code").asString()).isEqualTo("VALIDATION");
        assertThat(saved.getBody().get("fields").has("flow")).isTrue();
    }

    @Test
    void saveAppendsVersionOnlyWhenGraphChanged() {
        JsonNode bot = createChatbot("Bot", flow(List.of(node("n1", "wait", "Pause", Map.of("amount", 1, "unit", "HOURS"))), List.of()));
        String id = bot.get("id").asString();
        assertThat(versions.findAllByChatbotIdOrderByVersionNumberDesc(id)).hasSize(1);
        Map<String, Object> moved = node("n1", "wait", "Pause", Map.of("amount", 1, "unit", "HOURS"));
        moved.put("position", Map.of("x", 500, "y", 500));
        JsonNode after = bots(HttpMethod.PUT, "/" + id, manager, save(bot, flow(List.of(moved), List.of()), bot.get("version").asLong()))
                .getBody().get("chatbot");
        assertThat(versions.findAllByChatbotIdOrderByVersionNumberDesc(id)).hasSize(1);
        assertThat(after.get("versionNumber").asInt()).isEqualTo(1);
        Map<String, Object> grown = flow(List.of(moved, node("n2", "wait", "Pause2", Map.of("amount", 2, "unit", "HOURS"))),
                List.of(edge("n1", null, "n2")));
        JsonNode after2 = bots(HttpMethod.PUT, "/" + id, manager, save(after, grown, after.get("version").asLong()))
                .getBody().get("chatbot");
        List<ChatbotVersion> all = versions.findAllByChatbotIdOrderByVersionNumberDesc(id);
        assertThat(all).hasSize(2);
        assertThat(all.get(0).getVersionNumber()).isEqualTo(2);
        assertThat(after2.get("versionNumber").asInt()).isEqualTo(2);
    }

    @Test
    void staleSaveIs409() {
        JsonNode bot = createChatbot("Bot", flow(List.of(node("n1", "wait", "Pause", Map.of("amount", 1, "unit", "HOURS"))), List.of()));
        ResponseEntity<JsonNode> stale = bots(HttpMethod.PUT, "/" + bot.get("id").asString(), manager,
                save(bot, flow(List.of(), List.of()), 0L));
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void publishRefusedWithErrors() {
        JsonNode bot = createChatbot("Bot", flow(List.of(node("a", "wait", "A", Map.of("amount", 1, "unit", "HOURS")),
                node("b", "wait", "B", Map.of("amount", 1, "unit", "HOURS"))), List.of()));
        ResponseEntity<JsonNode> r = bots(HttpMethod.POST, "/" + bot.get("id").asString() + "/publish", manager, null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody().get("fields").has("flow.entry")).isTrue();
        assertThat(bots(HttpMethod.GET, "/" + bot.get("id").asString(), manager, null).getBody().get("status").asString())
                .isEqualTo("DRAFT");
    }

    @Test
    void publishedCannotBeSavedUntilUnpublished() {
        JsonNode bot = createChatbot("Bot", flow(List.of(node("a", "update_contact", "A", Map.of("nameIfBlank", "Friend"))), List.of()));
        String id = bot.get("id").asString();
        ResponseEntity<JsonNode> published = bots(HttpMethod.POST, "/" + id + "/publish", manager, null);
        assertThat(published.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(published.getBody().get("status").asString()).isEqualTo("PUBLISHED");
        assertThat(published.getBody().get("publishedBy").get("name").asString()).isNotBlank();
        ResponseEntity<JsonNode> save = bots(HttpMethod.PUT, "/" + id, manager,
                save(published.getBody(), flow(List.of(), List.of()), published.getBody().get("version").asLong()));
        assertThat(save.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(save.getBody().get("code").asString()).isEqualTo("CHATBOT_PUBLISHED");
        assertThat(bots(HttpMethod.DELETE, "/" + id, manager, null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode draft = bots(HttpMethod.POST, "/" + id + "/unpublish", manager, null).getBody();
        assertThat(draft.get("status").asString()).isEqualTo("DRAFT");
        ResponseEntity<JsonNode> ok = bots(HttpMethod.PUT, "/" + id, manager,
                save(draft, flow(List.of(node("a", "update_contact", "A", Map.of("nameIfBlank", "Pal"))), List.of()),
                        draft.get("version").asLong()));
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void restoreMakesNewVersion() {
        JsonNode bot = createChatbot("Bot", flow(List.of(node("a", "wait", "A", Map.of("amount", 1, "unit", "HOURS"))), List.of()));
        String id = bot.get("id").asString();
        JsonNode v2 = bots(HttpMethod.PUT, "/" + id, manager, save(bot,
                flow(List.of(node("a", "wait", "A", Map.of("amount", 5, "unit", "HOURS"))), List.of()), bot.get("version").asLong()))
                .getBody().get("chatbot");
        assertThat(v2.get("versionNumber").asInt()).isEqualTo(2);
        JsonNode list = bots(HttpMethod.GET, "/" + id + "/versions", manager, null).getBody();
        assertThat(list).hasSize(2);
        assertThat(list.get(0).get("versionNumber").asInt()).isEqualTo(2);
        assertThat(list.get(0).get("savedBy").get("name").asString()).isNotBlank();
        JsonNode v1flow = bots(HttpMethod.GET, "/" + id + "/versions/1", manager, null).getBody();
        assertThat(v1flow.get("nodes").get(0).get("data").get("settings").get("amount").asInt()).isEqualTo(1);
        ResponseEntity<JsonNode> restored = bots(HttpMethod.POST, "/" + id + "/versions/1/restore", manager, null);
        assertThat(restored.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(restored.getBody().get("chatbot").get("versionNumber").asInt()).isEqualTo(3);
        assertThat(restored.getBody().get("chatbot").get("flow").get("nodes").get(0).get("data").get("settings").get("amount").asInt())
                .isEqualTo(1);
    }

    @Test
    void viewerCannotSaveOrCreate() {
        // AGENT has neither chatbot authority by default (spec §9)
        assertThat(bots(HttpMethod.GET, "", agent, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(bots(HttpMethod.POST, "", agent, Map.of("name", "Bot")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        JsonNode bot = createChatbot("Bot", flow(List.of(node("a", "wait", "A", Map.of("amount", 1, "unit", "HOURS"))), List.of()));
        assertThat(bots(HttpMethod.PUT, "/" + bot.get("id").asString(), agent, save(bot, flow(List.of(), List.of()),
                bot.get("version").asLong())).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void otherBrandIs404() {
        JsonNode bot = createChatbot("Bot", flow(List.of(node("a", "wait", "A", Map.of("amount", 1, "unit", "HOURS"))), List.of()));
        ResponseEntity<JsonNode> r = call(HttpMethod.GET, "/api/workspaces/" + other.getId() + "/chatbots/" + bot.get("id").asString(),
                admin, null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void duplicateNameIs409() {
        bots(HttpMethod.POST, "", manager, Map.of("name", "Bot"));
        ResponseEntity<JsonNode> dup = bots(HttpMethod.POST, "", manager, Map.of("name", "bot"));
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(dup.getBody().get("code").asString()).isEqualTo("DUPLICATE");
        assertThat(dup.getBody().get("field").asString()).isEqualTo("name");
        assertThat(bots(HttpMethod.POST, "", manager, Map.of("name", "")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void optionsListAttributesLabelsAndChatbots() {
        createChatbot("Bot", flow(List.of(node("a", "wait", "A", Map.of("amount", 1, "unit", "HOURS"))), List.of()));
        JsonNode o = bots(HttpMethod.GET, "/options", manager, null).getBody();
        assertThat(o.get("chatbots")).hasSize(1);
        assertThat(o.get("chatbots").get(0).get("status").asString()).isEqualTo("DRAFT");
        assertThat(o.has("attributes")).isTrue();
        assertThat(o.has("labels")).isTrue();
        assertThat(o.has("templates")).isTrue();
        assertThat(o.has("agents")).isTrue();
    }
}
