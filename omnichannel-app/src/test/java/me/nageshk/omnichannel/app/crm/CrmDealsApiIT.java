package me.nageshk.omnichannel.app.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.crm.activity.ActivityType;
import me.nageshk.omnichannel.crm.activity.CrmActivity;
import me.nageshk.omnichannel.db.access.Role;
import me.nageshk.omnichannel.db.access.RoleLevel;
import me.nageshk.omnichannel.db.access.RoleRepository;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.db.user.WorkspaceRole;
import me.nageshk.omnichannel.security.access.RoleCache;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import tools.jackson.databind.JsonNode;

class CrmDealsApiIT extends CrmTestBase {

    @Autowired RoleRepository roles;
    @Autowired RoleCache roleCache;

    String sales;
    Map<String, String> lanes;
    String aliceId;
    String aliceDeal;

    @BeforeEach
    void setUp() throws Exception {
        enableCrm();
        sales = salesId();
        JsonNode c = captured(contact("Alice Wonder", "919811111111"), number);
        aliceId = c.get("contact").get("id").asText();
        aliceDeal = c.get("deal").get("id").asText();
        lanes = stages(sales);
    }

    private Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private JsonNode deal(String id) throws Exception {
        return json(send(get(crm() + "/deals/" + id), managerToken, null).andExpect(status().isOk()));
    }

    private JsonNode contactView(String id) throws Exception {
        return json(send(get(crm() + "/contacts/" + id), managerToken, null).andExpect(status().isOk()));
    }

    private String pipeline(String name) throws Exception {
        return json(send(post(crm() + "/pipelines"), managerToken, Map.of("name", name))
                .andExpect(status().is2xxSuccessful())).get("id").asText();
    }

    private long activities(ActivityType type, String targetId) {
        return mongo.count(new Query(Criteria.where("type").is(type).and("target.id").is(targetId)),
                CrmActivity.class);
    }

    @Test
    void captureCreatesDealInFirstOpenStage() throws Exception {
        WaWebChat chat = chat(contact("Bob Builder", "919822222222"), number, null, null);
        JsonNode first = json(send(post(crm() + "/contacts/capture"), managerToken, Map.of("chatId", chat.getId()))
                .andExpect(status().isOk()));
        assertThat(first.get("createdContact").asBoolean()).isTrue();
        assertThat(first.get("createdDeal").asBoolean()).isTrue();
        JsonNode d = first.get("deal");
        assertThat(d.get("title").asText()).isEqualTo("Bob Builder deal");
        assertThat(d.get("pipelineId").asText()).isEqualTo(sales);
        assertThat(d.get("stageId").asText()).isEqualTo(lanes.get("New"));
        assertThat(d.get("status").asText()).isEqualTo("OPEN");
        assertThat(d.get("probability").asInt()).isEqualTo(10);
        assertThat(d.get("currency").asText()).isEqualTo("INR");
        assertThat(d.get("owner").get("id").asText()).isEqualTo(manager.getId());
        assertThat(d.get("channel").get("phoneNumberId").asText()).isEqualTo(number.getId());
        assertThat(d.get("primaryContact").get("name").asText()).isEqualTo("Bob Builder");
        assertThat(d.get("primaryContact").get("phone").asText()).isEqualTo("919822222222");
        // the contact in the answer is read after the deal: counters and pipelines are current
        assertThat(first.get("contact").get("openDealCount").asInt()).isEqualTo(1);
        assertThat(activities(ActivityType.RECORD_CREATED, d.get("id").asText())).isEqualTo(1);

        JsonNode second = json(send(post(crm() + "/contacts/capture"), managerToken,
                Map.of("chatId", chat.getId(), "dealTitle", "Another")).andExpect(status().isOk()));
        assertThat(second.get("createdContact").asBoolean()).isFalse();
        assertThat(second.get("createdDeal").asBoolean()).isFalse();
        assertThat(second.get("deal").get("id").asText()).isEqualTo(d.get("id").asText());
        assertThat(mongo.count(new Query(Criteria.where("primaryCrmContactId")
                .is(first.get("contact").get("id").asText())), "crmDeals")).isEqualTo(1);

        // a dealTitle names the deal; another pipeline gets its own deal
        String second2 = pipeline("Second");
        JsonNode third = json(send(post(crm() + "/contacts/capture"), managerToken,
                Map.of("chatId", chat.getId(), "pipelineId", second2, "dealTitle", "Big order"))
                .andExpect(status().isOk()));
        assertThat(third.get("createdDeal").asBoolean()).isTrue();
        assertThat(third.get("deal").get("title").asText()).isEqualTo("Big order");
        assertThat(third.get("deal").get("pipelineId").asText()).isEqualTo(second2);
    }

    @Test
    void createNeedsVisiblePrimaryContact() throws Exception {
        send(post(crm() + "/deals"), managerToken, body("title", "X", "pipelineId", sales))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.primaryCrmContactId").exists());
        send(post(crm() + "/deals"), managerToken, body("pipelineId", sales, "primaryCrmContactId", aliceId))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.title").exists());
        send(post(crm() + "/deals"), managerToken, body("title", "x".repeat(201), "pipelineId", sales,
                "primaryCrmContactId", aliceId, "amount", -1, "probability", 101, "currency", "rupees",
                "expectedCloseDate", "2026-13-01"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.title").exists())
                .andExpect(jsonPath("$.fields.amount").exists()).andExpect(jsonPath("$.fields.probability").exists())
                .andExpect(jsonPath("$.fields.currency").exists())
                .andExpect(jsonPath("$.fields.expectedCloseDate").exists());
        send(post(crm() + "/deals"), managerToken, body("title", "X", "pipelineId", sales,
                "primaryCrmContactId", "nope")).andExpect(status().isNotFound());

        // Bob only has a deal in Second: invisible to an agent of Sales
        String second = pipeline("Second");
        WaWebChat bobChat = chat(contact("Bob", "919822222222"), number, null, null);
        String bobId = json(send(post(crm() + "/contacts/capture"), managerToken,
                Map.of("chatId", bobChat.getId(), "pipelineId", second))).get("contact").get("id").asText();
        assignPipelines(agent, sales);
        send(post(crm() + "/deals"), agentToken, body("title", "X", "pipelineId", sales,
                "primaryCrmContactId", bobId)).andExpect(status().isNotFound());
        send(post(crm() + "/deals"), agentToken, body("title", "X", "pipelineId", second,
                "primaryCrmContactId", aliceId)).andExpect(status().isNotFound());
        JsonNode mine = json(send(post(crm() + "/deals"), agentToken, body("title", "Mine", "pipelineId", sales,
                "primaryCrmContactId", aliceId, "amount", "12.50", "expectedCloseDate", "2026-12-31",
                "stageId", lanes.get("Qualified"))).andExpect(status().isOk()));
        assertThat(mine.get("owner").get("id").asText()).isEqualTo(agent.getId());
        assertThat(mine.get("stageId").asText()).isEqualTo(lanes.get("Qualified"));
        assertThat(mine.get("probability").asInt()).isEqualTo(50);
        assertThat(mine.get("expectedCloseDate").asText()).isEqualTo("2026-12-31");
        assertThat(mine.get("amount").decimalValue()).isEqualByComparingTo("12.50");
        assertThat(mine.get("crmContactIds").get(0).asText()).isEqualTo(aliceId);
        assertThat(mine.get("version").asLong()).isZero();
        Document stored = mongo.findOne(new Query(Criteria.where("_id").is(mine.get("id").asText())),
                Document.class, "crmDeals");
        assertThat(stored.get("amount")).isInstanceOf(Decimal128.class);
        assertThat(stored.getString("workspaceId")).isEqualTo(brand.getId());
        assertThat(stored.getString("customerId")).isEqualTo(acme.getId());
        assertThat(stored.getString("createdBy")).isEqualTo(agent.getId());
        assertThat(stored.get("channel", Document.class).getString("phoneNumberId")).isEqualTo(number.getId());
        assertThat(stored.getString("primaryCrmContactId")).isEqualTo(aliceId);
        // an inactive pipeline takes no new deals
        mongo.updateFirst(new Query(Criteria.where("_id").is(second)),
                new org.springframework.data.mongodb.core.query.Update().set("active", false), "crmPipelines");
        send(post(crm() + "/deals"), managerToken, body("title", "X", "pipelineId", second,
                "primaryCrmContactId", aliceId)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PIPELINE_INACTIVE"));
    }

    @Test
    void linkedContactOnOtherChannelIs409Mismatch() throws Exception {
        String carolId = captured(contact("Carol", "919833333333"), number2).get("contact").get("id").asText();
        String daveId = captured(contact("Dave", "919844444444"), number).get("contact").get("id").asText();
        send(post(crm() + "/deals"), managerToken, body("title", "X", "pipelineId", sales,
                "primaryCrmContactId", aliceId, "crmContactIds", List.of(carolId)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHANNEL_MISMATCH"));
        send(post(crm() + "/deals"), managerToken, body("title", "X", "pipelineId", sales,
                "primaryCrmContactId", aliceId, "crmContactIds", List.of("missing")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.crmContactIds").exists());
        JsonNode d = createDeal(body("title", "Shared", "pipelineId", sales, "primaryCrmContactId", aliceId,
                "crmContactIds", List.of(daveId), "amount", 40));
        assertThat(d.get("crmContactIds")).extracting(JsonNode::asText).containsExactly(aliceId, daveId);
        assertThat(contactView(daveId).get("openDealCount").asInt()).isEqualTo(2);
        // the same rule on edit
        send(put(crm() + "/deals/" + d.get("id").asText()), managerToken, body("version", d.get("version").asLong(),
                "crmContactIds", List.of(daveId, carolId)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHANNEL_MISMATCH"));
        // a new primary must be on the deal's number; the former primary stays linked
        String dealId = d.get("id").asText();
        long v = d.get("version").asLong();
        send(put(crm() + "/deals/" + dealId), managerToken, body("version", v, "primaryCrmContactId", carolId))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHANNEL_MISMATCH"));
        JsonNode swapped = json(send(put(crm() + "/deals/" + dealId), managerToken,
                body("version", v, "primaryCrmContactId", daveId)).andExpect(status().isOk()));
        assertThat(swapped.get("primaryContact").get("id").asText()).isEqualTo(daveId);
        assertThat(swapped.get("primaryContact").get("name").asText()).isEqualTo("Dave");
        assertThat(swapped.get("crmContactIds")).extracting(JsonNode::asText).containsExactly(daveId, aliceId);
    }

    @Test
    void updateRecordsFieldChangesAndGuardsTheVersion() throws Exception {
        JsonNode d = deal(aliceDeal);
        long v = d.get("version").asLong();
        String company = json(send(post(crm() + "/companies"), managerToken, Map.of("name", "Acme"))
                .andExpect(status().isOk())).get("id").asText();
        JsonNode u = json(send(put(crm() + "/deals/" + aliceDeal), managerToken, body("version", v, "title", "Renamed",
                "amount", "99.90", "companyId", company, "ownerId", agent.getId(), "source", "website",
                "probability", 40)).andExpect(status().isOk()));
        assertThat(u.get("title").asText()).isEqualTo("Renamed");
        assertThat(u.get("company").get("name").asText()).isEqualTo("Acme");
        assertThat(u.get("owner").get("id").asText()).isEqualTo(agent.getId());
        assertThat(u.get("probability").asInt()).isEqualTo(40);
        assertThat(u.get("probabilityOverridden").asBoolean()).isTrue();
        assertThat(u.get("version").asLong()).isGreaterThan(v);
        CrmActivity changed = mongo.findOne(new Query(Criteria.where("type").is(ActivityType.FIELD_CHANGED)
                .and("target.id").is(aliceDeal)), CrmActivity.class);
        assertThat(changed.getPipelineId()).isEqualTo(sales);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> changes = (List<Map<String, Object>>) changed.getPayload().get("changes");
        assertThat(changes).extracting(c -> (String) c.get("field"))
                .contains("title", "amount", "companyId", "ownerId", "source", "probability");
        JsonNode co = json(send(get(crm() + "/companies/" + company), managerToken, null));
        assertThat(co.get("openDealAmount").decimalValue()).isEqualByComparingTo("99.90");
        // a stale version, a stage change, an unknown company and an inactive source are refused
        send(put(crm() + "/deals/" + aliceDeal), managerToken, body("version", v, "title", "Again"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        long now = u.get("version").asLong();
        send(put(crm() + "/deals/" + aliceDeal), managerToken, body("version", now, "stageId", lanes.get("Won")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.stageId").exists());
        send(put(crm() + "/deals/" + aliceDeal), managerToken, body("version", now, "companyId", "nope"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.companyId").exists());
        send(put(crm() + "/deals/" + aliceDeal), managerToken, body("version", now, "source", "nope"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.source").exists());
        send(put(crm() + "/deals/" + aliceDeal), managerToken, body("title", "x"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.version").exists());
        // back to the stage's probability
        JsonNode back = json(send(put(crm() + "/deals/" + aliceDeal), managerToken, body("version", now,
                "probabilityOverridden", false)).andExpect(status().isOk()));
        assertThat(back.get("probability").asInt()).isEqualTo(10);
        assertThat(back.get("probabilityOverridden").asBoolean()).isFalse();
        // other brand and unknown ids
        send(get("/api/workspaces/" + other.getId() + "/crm/deals/" + aliceDeal), managerToken, null)
                .andExpect(status().is4xxClientError());
        send(get(crm() + "/deals/nope"), managerToken, null).andExpect(status().isNotFound());
    }

    @Test
    void moveToOtherPipelineNeedsBothAccessible() throws Exception {
        String second = pipeline("Second");
        Map<String, String> secondLanes = stages(second);
        assignPipelines(agent, sales);
        long v = deal(aliceDeal).get("version").asLong();
        send(put(crm() + "/deals/" + aliceDeal), agentToken, body("version", v, "pipelineId", second))
                .andExpect(status().isNotFound());
        assertThat(deal(aliceDeal).get("pipelineId").asText()).isEqualTo(sales);
        JsonNode moved = json(send(put(crm() + "/deals/" + aliceDeal), managerToken, body("version", v,
                "pipelineId", second)).andExpect(status().isOk()));
        assertThat(moved.get("pipelineId").asText()).isEqualTo(second);
        assertThat(moved.get("stageId").asText()).isEqualTo(secondLanes.get("New"));
        assertThat(mongo.findOne(new Query(Criteria.where("_id").is(aliceId)), Document.class, "crmContacts")
                .getList("pipelineIds", String.class)).containsExactly(second);
        CrmActivity a = mongo.findOne(new Query(Criteria.where("type").is(ActivityType.STAGE_CHANGED)
                .and("target.id").is(aliceDeal)), CrmActivity.class);
        assertThat(a.getPayload().get("fromPipelineId")).isEqualTo(sales);
        assertThat(a.getPayload().get("toPipelineId")).isEqualTo(second);
        assertThat(a.getPipelineId()).isEqualTo(second);
        // now in a pipeline the agent cannot see
        send(get(crm() + "/deals/" + aliceDeal), agentToken, null).andExpect(status().isNotFound());
        send(put(crm() + "/deals/" + aliceDeal), agentToken, body("version", moved.get("version").asLong(),
                "pipelineId", sales)).andExpect(status().isNotFound());
    }

    @Test
    void closeLostWithReasonRecordsDealLost() throws Exception {
        JsonNode lost = json(send(post(crm() + "/deals/" + aliceDeal + "/close"), managerToken,
                Map.of("outcome", "LOST", "lostReason", "price")).andExpect(status().isOk()));
        assertThat(lost.get("status").asText()).isEqualTo("LOST");
        assertThat(lost.get("stageId").asText()).isEqualTo(lanes.get("Lost"));
        assertThat(lost.get("lostReason").asText()).isEqualTo("price");
        assertThat(lost.get("closedAt").isNull()).isFalse();
        assertThat(lost.get("probability").asInt()).isZero();
        CrmActivity a = mongo.findOne(new Query(Criteria.where("type").is(ActivityType.DEAL_LOST)
                .and("target.id").is(aliceDeal)), CrmActivity.class);
        assertThat(a).isNotNull();
        assertThat(a.getPipelineId()).isEqualTo(sales);
        assertThat(a.getPayload().get("lostReason")).isEqualTo("price");
        assertThat(a.getRelatedTargets()).extracting(t -> t.id()).contains(aliceId);
        JsonNode won = json(send(post(crm() + "/deals/" + aliceDeal + "/close"), managerToken,
                Map.of("outcome", "WON")).andExpect(status().isOk()));
        assertThat(won.get("status").asText()).isEqualTo("WON");
        assertThat(won.get("lostReason").isNull()).isTrue();
        assertThat(activities(ActivityType.DEAL_WON, aliceDeal)).isEqualTo(1);
        // reopening clears closedAt and the reason
        JsonNode open = json(send(post(crm() + "/deals/" + aliceDeal + "/move"), managerToken,
                Map.of("stageId", lanes.get("New"))).andExpect(status().isOk()));
        assertThat(open.get("closedAt").isNull()).isTrue();
        assertThat(open.get("status").asText()).isEqualTo("OPEN");
    }

    @Test
    void closeWithUnknownReasonIs400() throws Exception {
        send(post(crm() + "/deals/" + aliceDeal + "/close"), managerToken,
                Map.of("outcome", "LOST", "lostReason", "nope"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.lostReason").exists());
        send(post(crm() + "/deals/" + aliceDeal + "/close"), managerToken, Map.of("outcome", "OPEN"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.outcome").exists());
        send(post(crm() + "/deals/" + aliceDeal + "/close"), managerToken, Map.of())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.outcome").exists());
        assertThat(deal(aliceDeal).get("status").asText()).isEqualTo("OPEN");
    }

    @Test
    void deleteNeedsDeleteAuthority() throws Exception {
        assignPipelines(agent, sales);
        send(delete(crm() + "/deals/" + aliceDeal), agentToken, null).andExpect(status().isForbidden());
        send(delete(crm() + "/deals/" + aliceDeal), managerToken, null).andExpect(status().isNoContent());
        send(get(crm() + "/deals/" + aliceDeal), managerToken, null).andExpect(status().isNotFound());
        send(delete(crm() + "/deals/" + aliceDeal), managerToken, null).andExpect(status().isNotFound());
    }

    @Test
    void contactCountersFollowDealWrites() throws Exception {
        JsonNode c = contactView(aliceId);
        assertThat(c.get("openDealCount").asInt()).isEqualTo(1);
        String big = createDeal(body("title", "Big", "pipelineId", sales, "primaryCrmContactId", aliceId,
                "amount", 100)).get("id").asText();
        c = contactView(aliceId);
        assertThat(c.get("openDealCount").asInt()).isEqualTo(2);
        assertThat(c.get("openDealAmount").decimalValue()).isEqualByComparingTo("100");
        send(post(crm() + "/deals/" + big + "/close"), managerToken, Map.of("outcome", "WON"))
                .andExpect(status().isOk());
        c = contactView(aliceId);
        assertThat(c.get("openDealCount").asInt()).isEqualTo(1);
        assertThat(c.get("openDealAmount").decimalValue()).isEqualByComparingTo("0");
        send(delete(crm() + "/deals/" + aliceDeal), managerToken, null).andExpect(status().isNoContent());
        c = contactView(aliceId);
        assertThat(c.get("openDealCount").asInt()).isZero();
        Document raw = mongo.findOne(new Query(Criteria.where("_id").is(aliceId)), Document.class, "crmContacts");
        assertThat(raw.getList("pipelineIds", String.class)).containsExactly(sales);
        send(delete(crm() + "/deals/" + big), managerToken, null).andExpect(status().isNoContent());
        raw = mongo.findOne(new Query(Criteria.where("_id").is(aliceId)), Document.class, "crmContacts");
        assertThat(raw.getList("pipelineIds", String.class)).isEmpty();
        assertThat(raw.getLong("version")).isZero();
    }

    private List<Map<String, Object>> stageRequests(String pipelineId, String remove, String moveTo)
            throws Exception {
        JsonNode p = json(send(get(crm() + "/pipelines/" + pipelineId), managerToken, null));
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode s : p.get("stages")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.get("id").asText());
            m.put("name", s.get("name").asText());
            m.put("type", s.get("type").asText());
            m.put("probability", s.get("probability").asInt());
            if (s.get("id").asText().equals(remove)) {
                if (moveTo == null) continue;
                m.put("moveToStageId", moveTo);
            }
            out.add(m);
        }
        return out;
    }

    @Test
    void stageRemovalRelocatesDeals() throws Exception {
        String contacted = lanes.get("Contacted");
        String qualified = lanes.get("Qualified");
        String already = createDeal(body("title", "Q", "pipelineId", sales, "primaryCrmContactId", aliceId,
                "stageId", qualified)).get("id").asText();
        send(post(crm() + "/deals/" + aliceDeal + "/move"), managerToken, Map.of("stageId", contacted))
                .andExpect(status().isOk());
        long v = json(send(get(crm() + "/pipelines/" + sales), managerToken, null)).get("version").asLong();
        send(put(crm() + "/pipelines/" + sales + "/stages"), managerToken,
                Map.of("version", v, "stages", stageRequests(sales, contacted, null)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STAGE_HAS_DEALS"))
                .andExpect(jsonPath("$.stageId").value(contacted)).andExpect(jsonPath("$.count").value("1"));
        send(put(crm() + "/pipelines/" + sales + "/stages"), managerToken,
                Map.of("version", v, "stages", stageRequests(sales, contacted, qualified)))
                .andExpect(status().isOk());
        assertThat(stages(sales)).doesNotContainKey("Contacted");
        JsonNode d = deal(aliceDeal);
        assertThat(d.get("stageId").asText()).isEqualTo(qualified);
        assertThat(d.get("probability").asInt()).isEqualTo(50);
        assertThat(d.get("position").asDouble()).isGreaterThan(deal(already).get("position").asDouble());
        assertThat(activities(ActivityType.STAGE_CHANGED, aliceDeal)).isEqualTo(2);
    }

    @Test
    void pipelineDeleteRelocatesDeals() throws Exception {
        String second = pipeline("Second");
        Map<String, String> secondLanes = stages(second);
        JsonNode d = createDeal(body("title", "S", "pipelineId", second, "primaryCrmContactId", aliceId,
                "stageId", secondLanes.get("Won")));
        assertThat(d.get("status").asText()).isEqualTo("WON");
        assertThat(d.get("closedAt").isNull()).isFalse();
        String lostId = createDeal(body("title", "L", "pipelineId", second, "primaryCrmContactId", aliceId))
                .get("id").asText();
        JsonNode lost = json(send(post(crm() + "/deals/" + lostId + "/close"), managerToken,
                Map.of("outcome", "LOST", "lostReason", "price")).andExpect(status().isOk()));
        String openId = createDeal(body("title", "O", "pipelineId", second, "primaryCrmContactId", aliceId,
                "stageId", secondLanes.get("Contacted"))).get("id").asText();
        assertThat(mongo.findOne(new Query(Criteria.where("_id").is(aliceId)), Document.class, "crmContacts")
                .getList("pipelineIds", String.class)).containsExactlyInAnyOrder(sales, second);
        send(delete(crm() + "/pipelines/" + second), managerToken, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PIPELINE_HAS_DEALS"))
                .andExpect(jsonPath("$.count").value("3"));
        send(delete(crm() + "/pipelines/" + second + "?moveDealsToPipelineId=" + sales), managerToken, null)
                .andExpect(status().isNoContent());
        // closed deals stay closed: WON to the first WON lane, LOST to the first LOST lane, both keep closedAt
        JsonNode won = deal(d.get("id").asText());
        assertThat(won.get("pipelineId").asText()).isEqualTo(sales);
        assertThat(won.get("stageId").asText()).isEqualTo(lanes.get("Won"));
        assertThat(won.get("status").asText()).isEqualTo("WON");
        assertThat(won.get("closedAt").asText()).isEqualTo(d.get("closedAt").asText());
        JsonNode stillLost = deal(lostId);
        assertThat(stillLost.get("stageId").asText()).isEqualTo(lanes.get("Lost"));
        assertThat(stillLost.get("status").asText()).isEqualTo("LOST");
        assertThat(stillLost.get("lostReason").asText()).isEqualTo("price");
        assertThat(stillLost.get("closedAt").asText()).isEqualTo(lost.get("closedAt").asText());
        JsonNode open = deal(openId);
        assertThat(open.get("stageId").asText()).isEqualTo(lanes.get("New"));
        assertThat(open.get("status").asText()).isEqualTo("OPEN");
        assertThat(open.get("position").asDouble()).isGreaterThan(deal(aliceDeal).get("position").asDouble());
        assertThat(activities(ActivityType.DEAL_WON, d.get("id").asText())).isEqualTo(1);
        assertThat(mongo.findOne(new Query(Criteria.where("_id").is(aliceId)), Document.class, "crmContacts")
                .getList("pipelineIds", String.class)).containsExactly(sales);

        // a target without a WON lane takes the deal into its first OPEN lane (reopened)
        String third = pipeline("Third");
        String wonThere = createDeal(body("title", "W", "pipelineId", third, "primaryCrmContactId", aliceId,
                "stageId", stages(third).get("Won"))).get("id").asText();
        String plain = json(send(post(crm() + "/pipelines"), managerToken, Map.of("name", "Plain",
                "stages", List.of(Map.of("name", "Only", "type", "OPEN"))))
                .andExpect(status().is2xxSuccessful())).get("id").asText();
        send(delete(crm() + "/pipelines/" + third + "?moveDealsToPipelineId=" + plain), managerToken, null)
                .andExpect(status().isNoContent());
        JsonNode reopened = json(send(get(crm() + "/deals/" + wonThere), managerToken, null));
        assertThat(reopened.get("stageId").asText()).isEqualTo(stages(plain).get("Only"));
        assertThat(reopened.get("status").asText()).isEqualTo("OPEN");
        assertThat(reopened.get("closedAt").isNull()).isTrue();
    }

    @Test
    void closingLostAgainWithoutAReasonKeepsTheReason() throws Exception {
        send(post(crm() + "/deals/" + aliceDeal + "/close"), managerToken,
                Map.of("outcome", "LOST", "lostReason", "price")).andExpect(status().isOk());
        JsonNode again = json(send(post(crm() + "/deals/" + aliceDeal + "/close"), managerToken,
                Map.of("outcome", "LOST")).andExpect(status().isOk()));
        assertThat(again.get("status").asText()).isEqualTo("LOST");
        assertThat(again.get("lostReason").asText()).isEqualTo("price");
        // entering LOST from OPEN without a reason stores none
        String other = createDeal(body("title", "B", "pipelineId", sales, "primaryCrmContactId", aliceId))
                .get("id").asText();
        JsonNode plain = json(send(post(crm() + "/deals/" + other + "/close"), managerToken,
                Map.of("outcome", "LOST")).andExpect(status().isOk()));
        assertThat(plain.get("lostReason").isNull()).isTrue();
    }

    @Test
    void createInAClosedStageRecordsTheOutcome() throws Exception {
        String won = createDeal(body("title", "W", "pipelineId", sales, "primaryCrmContactId", aliceId,
                "stageId", lanes.get("Won"), "amount", 10)).get("id").asText();
        String lost = createDeal(body("title", "L", "pipelineId", sales, "primaryCrmContactId", aliceId,
                "stageId", lanes.get("Lost"))).get("id").asText();
        assertThat(activities(ActivityType.RECORD_CREATED, won)).isEqualTo(1);
        assertThat(activities(ActivityType.DEAL_WON, won)).isEqualTo(1);
        assertThat(activities(ActivityType.DEAL_LOST, lost)).isEqualTo(1);
        assertThat(activities(ActivityType.DEAL_WON, aliceDeal) + activities(ActivityType.DEAL_LOST, aliceDeal))
                .isZero();
    }

    @Test
    void restrictedUserGets404OnDealsOfHiddenPipelines() throws Exception {
        String second = pipeline("Second");
        String hidden = createDeal(body("title", "Hidden", "pipelineId", second, "primaryCrmContactId", aliceId))
                .get("id").asText();
        assignPipelines(agent, sales);
        send(post(crm() + "/deals/" + hidden + "/move"), agentToken, Map.of("stageId", stages(second).get("Won")))
                .andExpect(status().isNotFound());
        send(post(crm() + "/deals/" + hidden + "/close"), agentToken, Map.of("outcome", "WON"))
                .andExpect(status().isNotFound());
        // deleting needs crm:delete, which agents lack: a custom role that has it but not crm:view-all
        Role deleter = new Role();
        deleter.setName("CRM Deleter");
        deleter.setLevel(RoleLevel.BRAND);
        deleter.setAuthorities(new HashSet<>(Set.of("crm:view", "crm:edit", "crm:delete")));
        deleter = roles.save(deleter);
        roleCache.evictAll();
        User cleaner = testUsers.createInCustomer(acme.getId(), "cleaner", null,
                List.of(new WorkspaceRole(brand.getId(), deleter.getId())));
        assignPipelines(cleaner, sales);
        String token = testUsers.accessToken(cleaner);
        send(delete(crm() + "/deals/" + hidden), token, null).andExpect(status().isNotFound());
        JsonNode untouched = deal(hidden);
        assertThat(untouched.get("status").asText()).isEqualTo("OPEN");
        assertThat(untouched.get("version").asLong()).isZero();
        // the same user may delete a deal it can see
        send(delete(crm() + "/deals/" + aliceDeal), token, null).andExpect(status().isNoContent());
    }

    @Test
    void partialAccessSearchSeesOnlyAccessiblePipelines() throws Exception {
        String second = pipeline("Second");
        WaWebChat bobChat = chat(contact("Bob", "919822222222"), number, null, null);
        JsonNode bob = json(send(post(crm() + "/contacts/capture"), managerToken,
                Map.of("chatId", bobChat.getId(), "pipelineId", second)).andExpect(status().isOk()));
        createDeal(body("title", "Alice in Second", "pipelineId", second, "primaryCrmContactId", aliceId));
        assignPipelines(agent, sales);
        JsonNode deals = json(send(post(crm() + "/deals/search"), agentToken, Map.of()).andExpect(status().isOk()));
        assertThat(deals.get("total").asLong()).isEqualTo(1);
        assertThat(deals.get("items").get(0).get("id").asText()).isEqualTo(aliceDeal);
        JsonNode bySecond = json(send(post(crm() + "/deals/search"), agentToken, Map.of("pipelineId", second))
                .andExpect(status().isOk()));
        assertThat(bySecond.get("total").asLong()).isZero();
        JsonNode contacts = json(send(post(crm() + "/contacts/search"), agentToken, Map.of())
                .andExpect(status().isOk()));
        assertThat(contacts.get("items")).extracting(c -> c.get("id").asText()).containsExactly(aliceId);
        assertThat(contacts.get("items")).extracting(c -> c.get("id").asText())
                .doesNotContain(bob.get("contact").get("id").asText());
        // the manager sees all three deals
        assertThat(json(send(post(crm() + "/deals/search"), managerToken, Map.of())).get("total").asLong())
                .isEqualTo(3);
    }

    @Test
    /** Phase B: only defined fields are accepted (Ruling B1); undefined or malformed keys are field errors. */
    void customFieldKeysAreChecked() throws Exception {
        for (String bad : List.of("a.b", "$set", "k".repeat(41), "budget")) {
            send(post(crm() + "/deals"), managerToken, body("title", "X", "pipelineId", sales,
                    "primaryCrmContactId", aliceId, "customFields", Map.of(bad, 1)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"))
                    .andExpect(jsonPath("$.fields['customFields." + bad + "']").exists());
            send(put(crm() + "/deals/" + aliceDeal), managerToken, body("version", 0, "customFields", Map.of(bad, 1)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields['customFields." + bad + "']").exists());
        }
        send(post(crm() + "/fields"), managerToken, Map.of("entityType", "DEAL", "key", "budget", "label", "Budget",
                "type", "CURRENCY")).andExpect(status().isCreated());
        JsonNode ok = createDeal(body("title", "X", "pipelineId", sales, "primaryCrmContactId", aliceId,
                "customFields", Map.of("budget", 5)));
        assertThat(ok.get("customFields").get("budget").asInt()).isEqualTo(5);
    }

    @Test
    void searchFiltersAndSorts() throws Exception {
        String bobId = captured(contact("Bob Marley", "919822222222"), number).get("contact").get("id").asText();
        createDeal(body("title", "Zebra", "pipelineId", sales, "primaryCrmContactId", aliceId, "amount", 5,
                "ownerId", agent.getId()));
        JsonNode all = json(send(post(crm() + "/deals/search"), managerToken, Map.of()).andExpect(status().isOk()));
        assertThat(all.get("total").asLong()).isEqualTo(3);
        JsonNode byTitle = json(send(post(crm() + "/deals/search"), managerToken, Map.of("q", "zeb")));
        assertThat(byTitle.get("total").asLong()).isEqualTo(1);
        JsonNode byName = json(send(post(crm() + "/deals/search"), managerToken, Map.of("q", "marley")));
        assertThat(byName.get("items").get(0).get("primaryContact").get("id").asText()).isEqualTo(bobId);
        JsonNode byContact = json(send(post(crm() + "/deals/search"), managerToken, Map.of("crmContactId", aliceId)));
        assertThat(byContact.get("total").asLong()).isEqualTo(2);
        JsonNode byOwner = json(send(post(crm() + "/deals/search"), managerToken, Map.of("ownerId", agent.getId())));
        assertThat(byOwner.get("total").asLong()).isEqualTo(1);
        JsonNode byStatus = json(send(post(crm() + "/deals/search"), managerToken, Map.of("status", "WON")));
        assertThat(byStatus.get("total").asLong()).isZero();
        JsonNode byAmount = json(send(post(crm() + "/deals/search"), managerToken,
                Map.of("sort", "amount", "dir", "desc", "size", 1)));
        assertThat(byAmount.get("items")).hasSize(1);
        assertThat(byAmount.get("items").get(0).get("title").asText()).isEqualTo("Zebra");
        send(post(crm() + "/deals/search"), managerToken, Map.of("sort", "bogus"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.sort").exists());
        send(post(crm() + "/deals/search"), managerToken, Map.of("status", "MAYBE"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.status").exists());
        // a restricted user without pipelines sees nothing
        assertThat(json(send(post(crm() + "/deals/search"), agentToken, Map.of())).get("total").asLong()).isZero();
    }

    @Test
    void companySummaryListsAccessibleOpenDeals() throws Exception {
        String company = json(send(post(crm() + "/companies"), managerToken, Map.of("name", "Acme"))
                .andExpect(status().isOk())).get("id").asText();
        String second = pipeline("Second");
        String open = createDeal(body("title", "Open", "pipelineId", sales, "primaryCrmContactId", aliceId,
                "companyId", company, "amount", 10)).get("id").asText();
        String won = createDeal(body("title", "Won", "pipelineId", sales, "primaryCrmContactId", aliceId,
                "companyId", company, "stageId", lanes.get("Won"))).get("id").asText();
        createDeal(body("title", "Hidden", "pipelineId", second, "primaryCrmContactId", aliceId,
                "companyId", company, "amount", 90));
        JsonNode all = json(send(get(crm() + "/companies/" + company + "/summary"), managerToken, null)
                .andExpect(status().isOk()));
        assertThat(all.get("openDeals")).hasSize(2);
        assertThat(all.get("openDealAmount").decimalValue()).isEqualByComparingTo("100");
        assertThat(all.get("openDeals")).extracting(d -> d.get("id").asText()).doesNotContain(won);
        assignPipelines(agent, sales);
        JsonNode mine = json(send(get(crm() + "/companies/" + company + "/summary"), agentToken, null)
                .andExpect(status().isOk()));
        assertThat(mine.get("openDeals")).extracting(d -> d.get("id").asText()).containsExactly(open);
    }

    @Test
    void crmDealsHasTheDeclaredIndexes() {
        var infos = mongo.indexOps("crmDeals").getIndexInfo();
        assertThat(infos).extracting(i -> i.getName()).contains("ws_lane", "ws_owner_status", "ws_primary",
                "ws_contacts", "ws_company", "ws_status_closed", "ws_status_activity", "ws_customFields");
        assertThat(infos).allSatisfy(i -> assertThat(i.getName().equals("_id_")
                || i.getIndexFields().get(0).getKey().equals("workspaceId")).isTrue());
    }
}
