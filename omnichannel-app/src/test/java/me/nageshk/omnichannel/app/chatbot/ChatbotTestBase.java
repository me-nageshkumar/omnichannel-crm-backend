package me.nageshk.omnichannel.app.chatbot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.app.automation.AutomationTestBase;
import me.nageshk.omnichannel.db.chatbot.Chatbot;
import me.nageshk.omnichannel.db.chatbot.ChatbotForm;
import me.nageshk.omnichannel.db.chatbot.ChatbotRun;
import me.nageshk.omnichannel.db.chatbot.ChatbotVersion;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** The automation fixture plus the chatbot collections and flow builders. */
public abstract class ChatbotTestBase extends AutomationTestBase {

    @Override
    protected List<Class<?>> collections() {
        List<Class<?>> c = new ArrayList<>(super.collections());
        c.add(Chatbot.class);
        c.add(ChatbotVersion.class);
        c.add(ChatbotRun.class);
        c.add(ChatbotForm.class);
        return c;
    }

    protected static Map<String, Object> node(String id, String type, String name, Map<String, Object> settings) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", name);
        data.put("settings", settings);
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", id);
        n.put("type", type);
        n.put("position", Map.of("x", 100, "y", 100));
        n.put("data", data);
        return n;
    }

    protected static Map<String, Object> edge(String source, String output, String target) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", source + "-" + output + "-" + target);
        e.put("source", source);
        if (output != null) e.put("sourceHandle", output);
        e.put("target", target);
        return e;
    }

    protected static Map<String, Object> flow(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("nodes", nodes);
        f.put("edges", edges);
        f.put("viewport", Map.of("x", 0, "y", 0, "zoom", 1));
        return f;
    }

    protected static Map<String, Object> textMessage(String body) {
        return Map.of("message", Map.of("type", "text", "text", Map.of("preview_url", false, "body", body)));
    }

    /** {@code path} is relative to the brand's {@code /api/workspaces/{id}/chatbots}. */
    protected ResponseEntity<JsonNode> bots(HttpMethod m, String path, me.nageshk.omnichannel.db.user.User who, Object body) {
        return rest.exchange(url("/api/workspaces/" + brand.getId() + "/chatbots" + path), m, auth(who, body),
                JsonNode.class);
    }

    /** Creates a draft chatbot as the manager and saves the flow; returns the saved chatbot view. */
    protected JsonNode createChatbot(String name, Map<String, Object> flow) {
        ResponseEntity<JsonNode> created = bots(HttpMethod.POST, "", manager, Map.of("name", name));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode bot = created.getBody();
        Map<String, Object> save = new LinkedHashMap<>();
        save.put("flow", flow);
        save.put("settings", mapper.convertValue(bot.get("settings"), Map.class));
        save.put("version", bot.get("version").asLong());
        ResponseEntity<JsonNode> saved = bots(HttpMethod.PUT, "/" + bot.get("id").asString(), manager, save);
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        return saved.getBody().get("chatbot");
    }
}
