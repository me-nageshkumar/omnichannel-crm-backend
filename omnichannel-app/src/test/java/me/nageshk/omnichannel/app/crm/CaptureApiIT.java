package me.nageshk.omnichannel.app.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import tools.jackson.databind.JsonNode;

class CaptureApiIT extends CrmTestBase {

    String sales;

    @BeforeEach
    void enable() {
        enableCrm();
        sales = "seed-sales-" + brand.getId();
        assignPipelines(manager2, sales); // first resolve seeds the default pipeline
    }

    private Map<String, Object> capture(WaWebChat chat, String pipelineId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chatId", chat.getId());
        if (pipelineId != null) body.put("pipelineId", pipelineId);
        return body;
    }

    private long stored() {
        return mongo.count(new Query(Criteria.where("workspaceId").is(brand.getId())), "crmContacts");
    }

    @Test
    void agentCapturesChatAssignedToThem() throws Exception {
        Contact c = contact("Alice", "919811111111");
        WaWebChat chat = chat(c, number, agent, null);
        assignPipelines(agent, sales);
        JsonNode v = json(send(post(crm() + "/contacts/capture"), agentToken, capture(chat, sales))
                .andExpect(status().isOk()));
        assertThat(v.get("createdContact").asBoolean()).isTrue();
        assertThat(v.get("createdDeal").asBoolean()).isTrue();
        assertThat(v.get("deal").get("pipelineId").asText()).isEqualTo(sales);
        assertThat(v.get("deal").get("owner").get("id").asText()).isEqualTo(agent.getId());
        JsonNode k = v.get("contact");
        assertThat(k.get("contactId").asText()).isEqualTo(c.getId());
        assertThat(k.get("channel").get("phoneNumberId").asText()).isEqualTo(number.getId());
        assertThat(k.get("origin").get("kind").asText()).isEqualTo("INBOUND_CHAT");
        assertThat(k.get("origin").get("chatId").asText()).isEqualTo(chat.getId());
        assertThat(k.get("origin").get("capturedBy").get("id").asText()).isEqualTo(agent.getId());
        assertThat(k.get("owner").get("id").asText()).isEqualTo(agent.getId());
        assertThat(k.get("lifecycleStatus").asText()).isEqualTo("lead");
        assertThat(k.get("name").asText()).isEqualTo("Alice");
        assertThat(k.get("contactDeleted").asBoolean()).isFalse();
        assertThat(stored()).isEqualTo(1);
    }

    @Test
    void agentCapturesChatAssignedToTheirTeam() throws Exception {
        WaWebChat chat = chat(contact("Alice", "919811111111"), number, null, support);
        assignPipelines(agent, sales);
        send(post(crm() + "/contacts/capture"), agentToken, capture(chat, sales)).andExpect(status().isOk())
                .andExpect(jsonPath("$.createdContact").value(true));
    }

    @Test
    void agentCannotCaptureUnassignedChat() throws Exception {
        WaWebChat chat = chat(contact("Alice", "919811111111"), number, null, null);
        assignPipelines(agent, sales);
        send(post(crm() + "/contacts/capture"), agentToken, capture(chat, sales)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CHAT_NOT_ASSIGNED"));
        assertThat(stored()).isZero();
    }

    @Test
    void agentWithoutPipelineCannotCapture() throws Exception {
        WaWebChat chat = chat(contact("Alice", "919811111111"), number, agent, null);
        send(post(crm() + "/contacts/capture"), agentToken, capture(chat, sales)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NO_PIPELINE_ACCESS"));
    }

    @Test
    void viewerWithoutEditCannotCapture() throws Exception {
        WaWebChat chat = chat(contact("Alice", "919811111111"), number, null, null);
        // staff has no crm authorities in the brand: refused before anything else (404 brand or 403)
        send(post(crm() + "/contacts/capture"), staffToken, capture(chat, sales)).andExpect(status().is4xxClientError());
    }

    @Test
    void managerCapturesAnyChatIntoDefault() throws Exception {
        WaWebChat chat = chat(contact("Alice", "919811111111"), number2, null, null);
        JsonNode v = json(send(post(crm() + "/contacts/capture"), managerToken, capture(chat, null))
                .andExpect(status().isOk()));
        assertThat(v.get("createdContact").asBoolean()).isTrue();
        assertThat(v.get("contact").get("channel").get("phoneNumberId").asText()).isEqualTo(number2.getId());
        assertThat(v.get("contact").get("owner").get("id").asText()).isEqualTo(manager.getId());
    }

    @Test
    void captureIsIdempotent() throws Exception {
        WaWebChat chat = chat(contact("Alice", "919811111111"), number, agent, null);
        assignPipelines(agent, sales);
        JsonNode first = json(send(post(crm() + "/contacts/capture"), agentToken, capture(chat, sales)));
        JsonNode second = json(send(post(crm() + "/contacts/capture"), agentToken, capture(chat, sales))
                .andExpect(status().isOk()));
        assertThat(first.get("createdDeal").asBoolean()).isTrue();
        assertThat(second.get("createdContact").asBoolean()).isFalse();
        assertThat(second.get("createdDeal").asBoolean()).isFalse();
        assertThat(second.get("deal").get("id").asText()).isEqualTo(first.get("deal").get("id").asText());
        assertThat(second.get("contact").get("id").asText()).isEqualTo(first.get("contact").get("id").asText());
        assertThat(mongo.count(new Query(Criteria.where("workspaceId").is(brand.getId())), "crmDeals")).isEqualTo(1);
        assertThat(stored()).isEqualTo(1);
    }

    @Test
    void restrictedCaptureNeedsPipelineId() throws Exception {
        WaWebChat chat = chat(contact("Alice", "919811111111"), number, agent, null);
        assignPipelines(agent, sales);
        send(post(crm() + "/contacts/capture"), agentToken, capture(chat, null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION")).andExpect(jsonPath("$.fields.pipelineId").exists());
    }

    @Test
    void inaccessiblePipelineIs404AndInactiveIs400() throws Exception {
        WaWebChat chat = chat(contact("Alice", "919811111111"), number, agent, null);
        JsonNode p2 = json(send(post(crm() + "/pipelines"), managerToken, Map.of("name", "Second"))
                .andExpect(status().is2xxSuccessful()));
        String second = p2.get("id").asText();
        assignPipelines(agent, sales);
        send(post(crm() + "/contacts/capture"), agentToken, capture(chat, second)).andExpect(status().isNotFound());
        assignPipelines(agent, sales, second);
        mongo.updateFirst(new Query(Criteria.where("_id").is(second)),
                new org.springframework.data.mongodb.core.query.Update().set("active", false), "crmPipelines");
        send(post(crm() + "/contacts/capture"), agentToken, capture(chat, second)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PIPELINE_INACTIVE"));
        assertThat(stored()).isZero();
    }

    @Test
    void createFromBoardOnChattableNumber() throws Exception {
        assignPipelines(agent, sales);
        Map<String, Object> body = Map.of("phone", "+91 98222 22222", "phoneNumberId", number.getId(),
                "firstName", "Bob", "lastName", "Builder", "pipelineId", sales);
        JsonNode v = json(send(post(crm() + "/contacts/create"), agentToken, body).andExpect(status().isOk()));
        assertThat(v.get("createdContact").asBoolean()).isTrue();
        assertThat(v.get("createdDeal").asBoolean()).isTrue();
        assertThat(v.get("deal").get("title").asText()).isEqualTo("Bob Builder deal");
        JsonNode k = v.get("contact");
        assertThat(k.get("origin").get("kind").asText()).isEqualTo("MANUAL");
        assertThat(k.get("name").asText()).isEqualTo("Bob Builder");
        assertThat(k.get("owner").get("id").asText()).isEqualTo(agent.getId());
        assertThat(k.get("phone").asText()).isEqualTo("919822222222");
        assertThat(contacts.findAll().stream().filter(x -> x.getWorkspaceId().equals(brand.getId())).toList())
                .hasSize(1);
        // the same phone on the same number is the same CRM contact
        send(post(crm() + "/contacts/create"), agentToken, body).andExpect(status().isOk())
                .andExpect(jsonPath("$.createdContact").value(false))
                .andExpect(jsonPath("$.createdDeal").value(false))
                .andExpect(jsonPath("$.deal.id").value(v.get("deal").get("id").asText()))
                .andExpect(jsonPath("$.contact.id").value(k.get("id").asText()));
    }

    @Test
    void createOnNumberNotChattableIs404() throws Exception {
        assignPipelines(agent, sales);
        send(post(crm() + "/contacts/create"), agentToken, Map.of("phone", "919822222222",
                "phoneNumberId", number2.getId(), "pipelineId", sales)).andExpect(status().isNotFound());
        assertThat(stored()).isZero();
    }

    @Test
    void createBadPhoneIs400() throws Exception {
        assignPipelines(agent, sales);
        send(post(crm() + "/contacts/create"), agentToken, Map.of("phone", "abc",
                "phoneNumberId", number.getId(), "pipelineId", sales)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION")).andExpect(jsonPath("$.fields.phone").exists());
    }

    @Test
    void sameNumberOnSecondChannelMakesSecondCrmContact() throws Exception {
        Contact c = contact("Alice", "919811111111");
        WaWebChat one = chat(c, number, null, null);
        WaWebChat two = chat(c, number2, null, null);
        String a = json(send(post(crm() + "/contacts/capture"), managerToken, capture(one, null)))
                .get("contact").get("id").asText();
        JsonNode b = json(send(post(crm() + "/contacts/capture"), managerToken, capture(two, null)));
        assertThat(b.get("createdContact").asBoolean()).isTrue();
        assertThat(b.get("contact").get("id").asText()).isNotEqualTo(a);
        assertThat(stored()).isEqualTo(2);
    }

    private Map<String, Object> add(String contactId, String channelAccountId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contactId", contactId);
        body.put("channelAccountId", channelAccountId);
        return body;
    }

    private long deals() {
        return mongo.count(new Query(Criteria.where("workspaceId").is(brand.getId())), "crmDeals");
    }

    @Test
    void addPutsAChannelContactInTheCrmWithoutADeal() throws Exception {
        Contact c = contact("Alice", "919811111111");
        JsonNode v = json(send(post(crm() + "/contacts/add"), managerToken, add(c.getId(), number.getId()))
                .andExpect(status().isOk()));
        assertThat(v.get("created").asBoolean()).isTrue();
        JsonNode k = v.get("contact");
        assertThat(k.get("contactId").asText()).isEqualTo(c.getId());
        assertThat(k.get("channel").get("phoneNumberId").asText()).isEqualTo(number.getId());
        assertThat(k.get("name").asText()).isEqualTo("Alice");
        assertThat(k.get("phone").asText()).isEqualTo("919811111111");
        assertThat(k.get("origin").get("kind").asText()).isEqualTo("MANUAL");
        assertThat(k.get("origin").get("capturedBy").get("id").asText()).isEqualTo(manager.getId());
        assertThat(k.get("owner").get("id").asText()).isEqualTo(manager.getId());
        assertThat(k.get("lifecycleStatus").asText()).isEqualTo("lead");
        assertThat(k.get("openDealCount").asInt()).isZero();
        assertThat(stored()).isEqualTo(1);
        assertThat(deals()).isZero();
        assertThat(mongo.count(new Query(Criteria.where("workspaceId").is(brand.getId()).and("type")
                .is("RECORD_CREATED")), "crmActivities")).isEqualTo(1);
    }

    @Test
    void addGivesBackTheContactAlreadyOnThatNumber() throws Exception {
        Contact c = contact("Alice", "919811111111");
        String id = captured(c, number).get("contact").get("id").asText();
        send(post(crm() + "/contacts/add"), managerToken, add(c.getId(), number.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(false)).andExpect(jsonPath("$.contact.id").value(id))
                .andExpect(jsonPath("$.contact.openDealCount").value(1));
        assertThat(stored()).isEqualTo(1);
        assertThat(deals()).isEqualTo(1);
    }

    @Test
    void addOnAnotherNumberIsASecondCrmContact() throws Exception {
        Contact c = contact("Alice", "919811111111");
        String id = captured(c, number).get("contact").get("id").asText();
        JsonNode v = json(send(post(crm() + "/contacts/add"), managerToken, add(c.getId(), number2.getId()))
                .andExpect(status().isOk()));
        assertThat(v.get("created").asBoolean()).isTrue();
        assertThat(v.get("contact").get("id").asText()).isNotEqualTo(id);
        assertThat(stored()).isEqualTo(2);
    }

    @Test
    void addRefusesAUserLimitedToPipelines() throws Exception {
        assignPipelines(agent, sales);
        send(post(crm() + "/contacts/add"), agentToken, add(contact("Alice", "919811111111").getId(), number.getId()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NO_PIPELINE_ACCESS"));
        assertThat(stored()).isZero();
    }

    @Test
    void addNeedsEdit() throws Exception {
        User viewer = brandUserWith("viewer", "CRM_VIEWER", java.util.Set.of("crm:view", "crm:view-all"));
        send(post(crm() + "/contacts/add"), testUsers.accessToken(viewer),
                add(contact("Alice", "919811111111").getId(), number.getId())).andExpect(status().isForbidden());
        assertThat(stored()).isZero();
    }

    @Test
    void addRefusesANumberOfTheBrandTheCallerCannotChatFrom() throws Exception {
        User worker = brandUserWith("worker", "CRM_WORKER", java.util.Set.of("crm:view", "crm:edit", "crm:view-all"));
        support.getMemberIds().add(worker.getId());
        teams.save(support);
        String token = testUsers.accessToken(worker);
        String alice = contact("Alice", "919811111111").getId();
        send(post(crm() + "/contacts/add"), token, add(alice, number2.getId())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Number not found"));
        assertThat(stored()).isZero();
        send(post(crm() + "/contacts/add"), token, add(alice, number.getId())).andExpect(status().isOk());
        assertThat(stored()).isEqualTo(1);
    }

    @Test
    void addChecksTheContactAndTheNumber() throws Exception {
        String alice = contact("Alice", "919811111111").getId();
        send(post(crm() + "/contacts/add"), managerToken, add(" ", number.getId()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.contactId").exists());
        send(post(crm() + "/contacts/add"), managerToken, add("nope", number.getId()))
                .andExpect(status().isNotFound());
        send(post(crm() + "/contacts/add"), managerToken,
                add(contact(other, "Bob", "919822222222").getId(), number.getId())).andExpect(status().isNotFound());
        send(post(crm() + "/contacts/add"), managerToken, add(alice, otherNumber.getId()))
                .andExpect(status().isNotFound());
        send(post(crm() + "/contacts/add"), managerToken, add(alice, null)).andExpect(status().isNotFound());
        assertThat(stored()).isZero();
    }
}
