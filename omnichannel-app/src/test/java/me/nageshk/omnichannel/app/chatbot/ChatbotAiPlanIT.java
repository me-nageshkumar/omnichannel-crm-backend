package me.nageshk.omnichannel.app.chatbot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.app.ai.AiTestBase;
import me.nageshk.omnichannel.app.support.TestBilling;
import me.nageshk.omnichannel.db.chatbot.ChatbotRun;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunRepository;
import me.nageshk.omnichannel.db.chatbot.IfRunning;
import me.nageshk.omnichannel.db.chatbot.StartedBy;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactRepository;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.service.chatbot.run.ChatbotRunService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** An AI turn spends the brand's own key, so none starts when the customer's plan does not cover AI. */
class ChatbotAiPlanIT extends AiTestBase {

    static final String VEENU = "919799750743@c.us";

    @Autowired ChatbotRunService runs;
    @Autowired ChatbotRunRepository runRepo;
    @Autowired ContactRepository contacts;
    @Autowired TestBilling billing;

    @BeforeEach
    void noPlan() {
        billing.reset();
    }

    @AfterEach
    void leaveNoPlan() {
        billing.reset();
    }

    private String publish(JsonNode bot) {
        ResponseEntity<JsonNode> res = bots(HttpMethod.POST, "/" + bot.get("id").asString() + "/publish", manager, null);
        assertThat(res.getStatusCode().is2xxSuccessful()).as(String.valueOf(res.getBody())).isTrue();
        return bot.get("id").asString();
    }

    private ChatbotRunService.StartResult start(String chatbotId, WaWebChat chat) {
        Contact c = contacts.findById(chat.getContactId()).orElseThrow();
        return runs.start(brand.getId(), chatbotId, c, chat, StartedBy.test(manager.getId()), null, IfRunning.SKIP, true);
    }

    private ChatbotRun run(String id) {
        return runRepo.findById(id).orElseThrow();
    }

    @Test
    void anAiNodeFailsWithoutACallWhenThePlanLacksAi() throws Exception {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", "Scripted");
        c.put("provider", "scripted");
        c.put("apiKey", "scripted-key-0123456789");
        ResponseEntity<JsonNode> connection =
                ai(HttpMethod.POST, "/connections", manager, c);
        assertThat(connection.getStatusCode().is2xxSuccessful()).as(String.valueOf(connection.getBody())).isTrue();
        String connectionId = connection.getBody().get("id").asString();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", "Sales");
        p.put("connectionId", connectionId);
        p.put("model", "scripted-1");
        p.put("instructions", "Be kind.");
        p.put("mcpServers", List.of());
        String profileId = ai(HttpMethod.POST, "/profiles", manager, p)
                .getBody().get("id").asString();
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(createChatbot("Bot", flow(List.of(node("ai", "ai", "Classify",
                Map.of("profileId", profileId, "mode", "SINGLE", "userMessage", "Classify the lead"))), List.of())));

        billing.assign(acme.getId(), Set.of("chatbots"), Map.of());
        ChatbotRunService.StartResult r = start(id, chat);
        assertThat(r.started()).isTrue();
        await(() -> !run(r.run().getId()).getStatus().active());

        assertThat(run(r.run().getId()).getSteps().get(0).output()).isEqualTo("failure");
        assertThat(mongo.count(new Query(), "aiCalls")).isZero();
    }
}
