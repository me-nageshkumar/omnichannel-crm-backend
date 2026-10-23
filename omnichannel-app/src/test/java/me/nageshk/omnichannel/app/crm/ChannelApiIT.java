package me.nageshk.omnichannel.app.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import tools.jackson.databind.JsonNode;

class ChannelApiIT extends CrmTestBase {

    Contact alice;
    WaWebChat aliceChat;
    String crmId;

    @BeforeEach
    void setUp() throws Exception {
        enableCrm();
        alice = contact("Alice", "919811111111");
        aliceChat = chat(alice, number, null, null);
        crmId = json(send(post(crm() + "/contacts/capture"), managerToken, Map.of("chatId", aliceChat.getId()))
                .andExpect(status().isOk())).get("contact").get("id").asText();
    }

    private void allowMove(boolean on) {
        mongo.updateFirst(new Query(Criteria.where("workspaceId").is(brand.getId())),
                new Update().set("allowChannelMove", on), "crmSettings");
    }

    @Test
    void communicationNamesChatOnChannel() throws Exception {
        JsonNode v = json(send(get(crm() + "/contacts/" + crmId + "/communication"), managerToken, null)
                .andExpect(status().isOk()));
        assertThat(v.get("phoneNumberId").asText()).isEqualTo(number.getId());
        assertThat(v.get("number").asText()).isEqualTo(number.getNumber());
        assertThat(v.get("phone").asText()).isEqualTo("919811111111");
        assertThat(v.get("chatId").asText()).isEqualTo(aliceChat.getId());
        assertThat(v.get("canChat").asBoolean()).isTrue();
        send(get(crm() + "/contacts/" + crmId + "/communication?phoneNumberId=" + number.getId()), managerToken, null)
                .andExpect(status().isOk());
    }

    @Test
    void communicationWithOtherNumberIs409Mismatch() throws Exception {
        send(get(crm() + "/contacts/" + crmId + "/communication?phoneNumberId=" + number2.getId()), managerToken, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHANNEL_MISMATCH"));
    }

    @Test
    void canChatFalseForAgentOnNumber2() throws Exception {
        Contact bob = contact("Bob", "919833333333");
        WaWebChat bobChat = chat(bob, number2, null, null);
        String id = json(send(post(crm() + "/contacts/capture"), managerToken, Map.of("chatId", bobChat.getId())))
                .get("contact").get("id").asText();
        // an agent sees contacts of their pipelines; give the contact a deal-less visibility through a raw deal
        mongo.insert(new Document("workspaceId", brand.getId()).append("primaryCrmContactId", id)
                .append("pipelineId", "seed-sales-" + brand.getId()), "crmDeals");
        mongo.updateFirst(new Query(Criteria.where("_id").is(id)),
                new Update().set("pipelineIds", List.of("seed-sales-" + brand.getId())), "crmContacts");
        assignPipelines(agent, "seed-sales-" + brand.getId());
        JsonNode v = json(send(get(crm() + "/contacts/" + id + "/communication"), agentToken, null)
                .andExpect(status().isOk()));
        assertThat(v.get("canChat").asBoolean()).isFalse();
        assertThat(v.get("chatId").asText()).isEqualTo(bobChat.getId());
    }

    @Test
    void managerMovesChannelAndHistoryRecords() throws Exception {
        mongo.insert(new Document("workspaceId", brand.getId()).append("primaryCrmContactId", crmId)
                .append("channel", new Document("phoneNumberId", number.getId()).append("number", "x")), "crmDeals");
        long version = json(send(get(crm() + "/contacts/" + crmId), managerToken, null)).get("version").asLong();
        JsonNode v = json(send(post(crm() + "/contacts/" + crmId + "/move-channel"), managerToken,
                Map.of("toPhoneNumberId", number2.getId(), "reason", "customer asked", "version", version))
                .andExpect(status().isOk()));
        assertThat(v.get("channel").get("phoneNumberId").asText()).isEqualTo(number2.getId());
        assertThat(v.get("channel").get("number").asText()).isEqualTo(number2.getNumber());
        JsonNode h = v.get("channelHistory").get(0);
        assertThat(h.get("from").asText()).isEqualTo(number.getId());
        assertThat(h.get("to").asText()).isEqualTo(number2.getId());
        assertThat(h.get("by").get("id").asText()).isEqualTo(manager.getId());
        assertThat(h.get("reason").asText()).isEqualTo("customer asked");
        Document deal = mongo.findOne(new Query(), Document.class, "crmDeals");
        assertThat(deal.get("channel", Document.class).getString("phoneNumberId")).isEqualTo(number2.getId());
        assertThat(mongo.count(new Query(Criteria.where("type").is(me.nageshk.omnichannel.crm.activity.ActivityType.CHANNEL_CHANGED)
                .and("target.id").is(crmId)), me.nageshk.omnichannel.crm.activity.CrmActivity.class)).isEqualTo(1);
    }

    @Test
    void moveToNumberWithExistingCrmContactIs409Taken() throws Exception {
        WaWebChat other2 = chat(alice, number2, null, null);
        send(post(crm() + "/contacts/capture"), managerToken, Map.of("chatId", other2.getId()))
                .andExpect(status().isOk());
        long version = json(send(get(crm() + "/contacts/" + crmId), managerToken, null)).get("version").asLong();
        send(post(crm() + "/contacts/" + crmId + "/move-channel"), managerToken,
                Map.of("toPhoneNumberId", number2.getId(), "version", version))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHANNEL_TAKEN"));
    }

    @Test
    void moveRejectsSameNumberOtherBrandNumberAndStaleVersion() throws Exception {
        long version = json(send(get(crm() + "/contacts/" + crmId), managerToken, null)).get("version").asLong();
        send(post(crm() + "/contacts/" + crmId + "/move-channel"), managerToken,
                Map.of("toPhoneNumberId", number.getId(), "version", version))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"));
        send(post(crm() + "/contacts/" + crmId + "/move-channel"), managerToken,
                Map.of("toPhoneNumberId", otherNumber.getId(), "version", version)).andExpect(status().isNotFound());
        send(post(crm() + "/contacts/" + crmId + "/move-channel"), managerToken,
                Map.of("toPhoneNumberId", number2.getId(), "version", version + 5))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    @Test
    void agentCannotMove() throws Exception {
        send(post(crm() + "/contacts/" + crmId + "/move-channel"), agentToken,
                Map.of("toPhoneNumberId", number2.getId(), "version", 0)).andExpect(status().isForbidden());
    }

    @Test
    void moveRefusedWhenSettingOff() throws Exception {
        allowMove(false);
        send(post(crm() + "/contacts/" + crmId + "/move-channel"), managerToken,
                Map.of("toPhoneNumberId", number2.getId(), "version", 0)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
