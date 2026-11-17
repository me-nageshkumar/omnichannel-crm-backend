package me.nageshk.omnichannel.app.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.app.support.TestBilling;
import me.nageshk.omnichannel.app.support.TestUsers;
import me.nageshk.omnichannel.common.billing.LimitKey;
import me.nageshk.omnichannel.db.chatbot.Chatbot;
import me.nageshk.omnichannel.db.chatbot.ChatbotRepository;
import me.nageshk.omnichannel.db.tenant.Customer;
import me.nageshk.omnichannel.db.tenant.Workspace;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.security.access.BuiltInRoles;
import me.nageshk.omnichannel.security.entitlement.Limits;
import me.nageshk.omnichannel.service.chatbot.ChatbotService;
import me.nageshk.omnichannel.service.chatbot.node.Problem;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** A plan's caps on automation rules, chatbots, chatbot size and AI profiles, counted across the customer's brands. */
@SpringBootTest
@AutoConfigureMockMvc
class FeatureLimitsIT {

    private static final Set<String> FEATURES = Set.of("automations", "chatbots", "ai");

    @Autowired MockMvc mvc;
    @Autowired TestUsers testUsers;
    @Autowired TestBilling billing;
    @Autowired MongoTemplate mongo;
    @Autowired Limits limits;
    @Autowired ChatbotRepository chatbots;
    @Autowired ChatbotService chatbotService;

    Customer customer;
    Workspace ws;
    Workspace second;
    String adminToken;

    @BeforeEach
    void setUp() {
        testUsers.deleteAll();
        billing.reset();
        for (String c : List.of("automationRules", "aiProfiles")) mongo.remove(new Query(), c);
        customer = testUsers.customer("Acme");
        ws = testUsers.workspace(customer.getId(), "Main");
        second = testUsers.workspace(customer.getId(), "Second");
        User admin = testUsers.createInCustomer(customer.getId(), "feat-admin", BuiltInRoles.CUSTOMER_ADMIN, List.of());
        adminToken = testUsers.accessToken(admin);
    }

    @Test
    void rulesAreCountedAcrossTheCustomersBrands() throws Exception {
        billing.assign(customer.getId(), FEATURES, Map.of(LimitKey.AUTOMATION_RULES, 2));
        insert("automationRules", ws);
        // under the cap the request is looked at (and this empty one is refused for what it lacks)
        post("/automations/rules", "{}").andExpect(status().isBadRequest());

        insert("automationRules", second);
        assertThat(limits.used(customer.getId(), LimitKey.AUTOMATION_RULES)).hasValue(2);
        post("/automations/rules", "{}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LIMIT_REACHED"))
                .andExpect(jsonPath("$.key").value("AUTOMATION_RULES"));
    }

    @Test
    void aiProfilesStopAtTheCap() throws Exception {
        billing.assign(customer.getId(), FEATURES, Map.of(LimitKey.AI_PROFILES, 1));
        post("/ai/profiles", "{}").andExpect(status().isBadRequest());
        insert("aiProfiles", second);
        post("/ai/profiles", "{}").andExpect(status().isConflict()).andExpect(jsonPath("$.key").value("AI_PROFILES"));
    }

    @Test
    void chatbotsStopAtTheCap() throws Exception {
        billing.assign(customer.getId(), FEATURES, Map.of(LimitKey.CHATBOTS, 1));
        post("/chatbots", "{\"name\":\"First\"}").andExpect(status().isCreated());
        post("/chatbots", "{\"name\":\"Second\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.key").value("CHATBOTS"));
    }

    @Test
    void aChatbotOverTheNodeCapIsKeptButCannotBePublished() throws Exception {
        billing.assign(customer.getId(), FEATURES, Map.of());
        post("/chatbots", "{\"name\":\"Big\"}").andExpect(status().isCreated());
        Chatbot bot = chatbots.findAll().stream().filter(c -> c.getName().equals("Big")).findFirst().orElseThrow();
        bot.setFlow(threeWaits());
        chatbots.save(bot);
        assertThat(planProblems(bot)).isEmpty();

        billing.assign(customer.getId(), FEATURES, Map.of(LimitKey.CHATBOT_NODES, 2));

        assertThat(planProblems(bot)).containsExactly("Your plan allows 2 nodes in a chatbot");
        post("/chatbots/" + bot.getId() + "/publish", "").andExpect(status().is4xxClientError());
        assertThat(chatbots.findById(bot.getId()).orElseThrow().getStatus().name()).isEqualTo("DRAFT");
    }

    @Test
    void withoutAPlanNothingIsCapped() throws Exception {
        insert("automationRules", ws);
        insert("automationRules", ws);
        post("/automations/rules", "{}").andExpect(status().isBadRequest());
        post("/chatbots", "{\"name\":\"One\"}").andExpect(status().isCreated());
        post("/chatbots", "{\"name\":\"Two\"}").andExpect(status().isCreated());
    }

    private List<String> planProblems(Chatbot bot) {
        return chatbotService.problems(chatbots.findById(bot.getId()).orElseThrow()).stream()
                .filter(p -> "plan".equals(p.field())).map(Problem::message).toList();
    }

    private void insert(String collection, Workspace in) {
        mongo.insert(new Document("customerId", customer.getId()).append("workspaceId", in.getId())
                .append("name", "seed-" + System.nanoTime()), collection);
    }

    private ResultActions post(String path, String body) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/workspaces/" + ws.getId() + path).header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static Map<String, Object> threeWaits() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (String id : List.of("a", "b", "c")) {
            Map<String, Object> data = new HashMap<>();
            data.put("name", id.toUpperCase());
            data.put("settings", Map.of("seconds", 5));
            Map<String, Object> n = new HashMap<>();
            n.put("id", id);
            n.put("type", "wait");
            n.put("data", data);
            nodes.add(n);
        }
        Map<String, Object> flow = new HashMap<>();
        flow.put("nodes", nodes);
        flow.put("edges", List.of(Map.of("id", "e1", "source", "a", "target", "b"),
                Map.of("id", "e2", "source", "b", "target", "c")));
        return flow;
    }
}
