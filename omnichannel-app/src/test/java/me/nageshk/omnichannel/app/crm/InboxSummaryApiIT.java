package me.nageshk.omnichannel.app.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class InboxSummaryApiIT extends CrmTestBase {

    String sales;

    @BeforeEach
    void enable() {
        enableCrm();
        sales = salesId();
        assignPipelines(manager2, sales);
    }

    private JsonNode summary(String token, String chatId) throws Exception {
        return json(send(get(crm() + "/contacts/by-chat/" + chatId + "/summary"), token, null)
                .andExpect(status().isOk()));
    }

    @Test
    void uncapturedAssignedChatCanCapture() throws Exception {
        WaWebChat chat = chat(contact("Alice", "919811111111"), number, agent, null);
        assignPipelines(agent, sales);
        JsonNode s = summary(agentToken, chat.getId());
        assertThat(s.get("chatId").asText()).isEqualTo(chat.getId());
        assertThat(s.get("canCapture").asBoolean()).isTrue();
        assertThat(s.get("captureBlockedReason").isNull()).isTrue();
        assertThat(s.get("contact").isNull()).isTrue();
        assertThat(s.get("openDeals")).isEmpty();
        assertThat(s.get("nextTask").isNull()).isTrue();
    }

    @Test
    void unassignedChatBlockedWithReason() throws Exception {
        WaWebChat chat = chat(contact("Alice", "919811111111"), number, null, null);
        assignPipelines(agent, sales);
        JsonNode s = summary(agentToken, chat.getId());
        assertThat(s.get("canCapture").asBoolean()).isFalse();
        assertThat(s.get("captureBlockedReason").asText()).isEqualTo("CHAT_NOT_ASSIGNED");
        // no pipeline at all wins over the assignment
        WaWebChat mine = chat(contact("Bob", "919822222222"), number, agent2, null);
        assertThat(summary(agent2Token, mine.getId()).get("captureBlockedReason").asText())
                .isEqualTo("NO_PIPELINE_ACCESS");
        // a manager may capture anything
        assertThat(summary(managerToken, chat.getId()).get("canCapture").asBoolean()).isTrue();
    }

    @Test
    void capturedChatShowsContactAndOpenDeals() throws Exception {
        Contact c = contact("Alice", "919811111111");
        WaWebChat chat = chat(c, number, null, null);
        JsonNode cap = json(send(post(crm() + "/contacts/capture"), managerToken, Map.of("chatId", chat.getId()))
                .andExpect(status().isOk()));
        String dealId = cap.get("deal").get("id").asText();
        JsonNode s = summary(managerToken, chat.getId());
        assertThat(s.get("contact").get("id").asText()).isEqualTo(cap.get("contact").get("id").asText());
        assertThat(s.get("openDeals")).hasSize(1);
        assertThat(s.get("openDeals").get(0).get("id").asText()).isEqualTo(dealId);
        // a second pipeline's deal is hidden from a restricted agent
        String second = json(send(post(crm() + "/pipelines"), managerToken, Map.of("name", "Second"))
                .andExpect(status().is2xxSuccessful())).get("id").asText();
        createDeal(Map.of("title", "Other", "pipelineId", second, "primaryCrmContactId",
                cap.get("contact").get("id").asText()));
        assertThat(summary(managerToken, chat.getId()).get("openDeals")).hasSize(2);
        assignPipelines(agent, sales);
        JsonNode asAgent = summary(agentToken, chat.getId());
        assertThat(asAgent.get("openDeals")).hasSize(1);
        assertThat(asAgent.get("contact").isNull()).isFalse();
        // a closed deal is not an open deal
        send(post(crm() + "/deals/" + dealId + "/close"), managerToken, Map.of("outcome", "WON"))
                .andExpect(status().isOk());
        assertThat(summary(agentToken, chat.getId()).get("openDeals")).isEmpty();
    }

    @Test
    void contactWithOnlyInaccessibleDealsIsNull() throws Exception {
        Contact c = contact("Alice", "919811111111");
        WaWebChat chat = chat(c, number, null, null);
        String second = json(send(post(crm() + "/pipelines"), managerToken, Map.of("name", "Second"))
                .andExpect(status().is2xxSuccessful())).get("id").asText();
        send(post(crm() + "/contacts/capture"), managerToken, Map.of("chatId", chat.getId(), "pipelineId", second))
                .andExpect(status().isOk());
        assignPipelines(agent, sales);
        JsonNode s = summary(agentToken, chat.getId());
        assertThat(s.get("contact").isNull()).isTrue();
        assertThat(s.get("openDeals")).isEmpty();
    }

    @Test
    void otherBrandChatIs404() throws Exception {
        WaWebChat chat = chat(contact(other, "Zed", "919833333333"), otherNumber, null, null);
        send(get(crm() + "/contacts/by-chat/" + chat.getId() + "/summary"), managerToken, null)
                .andExpect(status().isNotFound());
    }
}
