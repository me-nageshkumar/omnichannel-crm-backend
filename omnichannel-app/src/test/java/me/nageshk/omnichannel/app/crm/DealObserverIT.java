package me.nageshk.omnichannel.app.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.crm.platform.DealLaneChange;
import me.nageshk.omnichannel.crm.platform.DealObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

/** Every deal move through the CRM's API reaches the DealObserver once; relocations and creation stay silent. */
class DealObserverIT extends CrmTestBase {

    @MockitoSpyBean DealObserver observer;

    String sales;
    Map<String, String> lanes;
    String contactId;
    String dealId;
    String crmContactId;

    @BeforeEach
    void setUp() throws Exception {
        enableCrm();
        sales = salesId();
        var contact = contact("Alice Wonder", "919811111111");
        JsonNode c = captured(contact, number);
        crmContactId = c.get("contact").get("id").asText();
        contactId = contact.getId();
        dealId = c.get("deal").get("id").asText();
        lanes = stages(sales);
        reset(observer);
    }

    private DealLaneChange only() {
        ArgumentCaptor<DealLaneChange> c = ArgumentCaptor.forClass(DealLaneChange.class);
        verify(observer, times(1)).laneChanged(c.capture());
        return c.getValue();
    }

    private JsonNode deal(String id) throws Exception {
        return json(send(get(crm() + "/deals/" + id), managerToken, null).andExpect(status().isOk()));
    }

    private String pipeline(String name) throws Exception {
        return json(send(post(crm() + "/pipelines"), managerToken, Map.of("name", name))
                .andExpect(status().is2xxSuccessful())).get("id").asText();
    }

    private Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void boardMoveReports() throws Exception {
        String first = deal(dealId).get("stageId").asText();
        String second = lanes.get("Contacted");
        send(post(crm() + "/deals/" + dealId + "/move"), managerToken, Map.of("stageId", second))
                .andExpect(status().isOk());
        DealLaneChange c = only();
        assertThat(c.from().id()).isEqualTo(first);
        assertThat(c.to().id()).isEqualTo(second);
        assertThat(c.to().type()).isEqualTo("OPEN");
        assertThat(c.contactId()).isEqualTo(contactId);
        assertThat(c.phoneNumberId()).isEqualTo(number.getId());
        assertThat(c.dealId()).isEqualTo(dealId);
        assertThat(c.workspaceId()).isEqualTo(brand.getId());
        assertThat(c.pipelineId()).isEqualTo(sales);
        assertThat(c.at()).isNotNull();
    }

    @Test
    void closeWonReports() throws Exception {
        send(post(crm() + "/deals/" + dealId + "/close"), managerToken, Map.of("outcome", "WON"))
                .andExpect(status().isOk());
        DealLaneChange c = only();
        assertThat(c.to().type()).isEqualTo("WON");
        assertThat(c.to().id()).isEqualTo(lanes.get("Won"));
        assertThat(c.status()).isEqualTo("WON");
    }

    @Test
    void pipelineChangeReports() throws Exception {
        String second = pipeline("Second");
        long v = deal(dealId).get("version").asLong();
        send(put(crm() + "/deals/" + dealId), managerToken, body("version", v, "pipelineId", second))
                .andExpect(status().isOk());
        DealLaneChange c = only();
        assertThat(c.pipelineId()).isEqualTo(second);
        assertThat(c.pipelineName()).isEqualTo("Second");
        assertThat(c.from().id()).isEqualTo(lanes.get("New"));
        assertThat(c.to().id()).isEqualTo(stages(second).get("New"));
    }

    /** The form cannot change the stage within a pipeline (it answers 400, "move the deal instead"): no lane change. */
    @Test
    void formStageChangeReports() throws Exception {
        long v = deal(dealId).get("version").asLong();
        send(put(crm() + "/deals/" + dealId), managerToken, body("version", v, "stageId", lanes.get("Contacted")))
                .andExpect(status().isBadRequest());
        verify(observer, never()).laneChanged(any());
        // the form with a title change alone stays silent too
        send(put(crm() + "/deals/" + dealId), managerToken, body("version", v, "title", "Renamed"))
                .andExpect(status().isOk());
        verify(observer, never()).laneChanged(any());
    }

    @Test
    void sameLaneIsSilent() throws Exception {
        String current = deal(dealId).get("stageId").asText();
        send(post(crm() + "/deals/" + dealId + "/move"), managerToken, Map.of("stageId", current))
                .andExpect(status().isOk());
        verify(observer, never()).laneChanged(any());

        send(post(crm() + "/deals/" + dealId + "/close"), managerToken,
                Map.of("outcome", "LOST", "lostReason", "price")).andExpect(status().isOk());
        verify(observer, times(1)).laneChanged(any());
        reset(observer);
        send(post(crm() + "/deals/" + dealId + "/close"), managerToken,
                Map.of("outcome", "LOST", "lostReason", "price"));
        verify(observer, never()).laneChanged(any());
    }

    @Test
    void stageDeletionRelocatesSilently() throws Exception {
        String contacted = lanes.get("Contacted");
        String qualified = lanes.get("Qualified");
        send(post(crm() + "/deals/" + dealId + "/move"), managerToken, Map.of("stageId", contacted))
                .andExpect(status().isOk());
        reset(observer);
        JsonNode p = json(send(get(crm() + "/pipelines/" + sales), managerToken, null));
        long v = p.get("version").asLong();
        send(put(crm() + "/pipelines/" + sales + "/stages"), managerToken,
                Map.of("version", v, "stages", stagesWith(p, contacted, qualified))).andExpect(status().isOk());
        assertThat(deal(dealId).get("stageId").asText()).isEqualTo(qualified);
        verify(observer, never()).laneChanged(any());
    }

    private List<Map<String, Object>> stagesWith(JsonNode p, String remove, String moveTo) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode s : p.get("stages")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.get("id").asText());
            m.put("name", s.get("name").asText());
            m.put("type", s.get("type").asText());
            m.put("probability", s.get("probability").asInt());
            if (s.get("id").asText().equals(remove)) m.put("moveToStageId", moveTo);
            out.add(m);
        }
        return out;
    }

    @Test
    void pipelineDeletionRelocatesSilently() throws Exception {
        String second = pipeline("Second");
        String there = createDeal(body("title", "S", "pipelineId", second, "primaryCrmContactId", crmContactId))
                .get("id").asText();
        reset(observer);
        send(delete(crm() + "/pipelines/" + second + "?moveDealsToPipelineId=" + sales), managerToken, null)
                .andExpect(status().isNoContent());
        assertThat(deal(there).get("pipelineId").asText()).isEqualTo(sales);
        verify(observer, never()).laneChanged(any());
    }

    @Test
    void creationIsSilent() throws Exception {
        createDeal(body("title", "Fresh", "pipelineId", sales, "primaryCrmContactId", crmContactId));
        verify(observer, never()).laneChanged(any());
    }

    @Test
    void dealMoveSurvivesObserverFailure() throws Exception {
        doThrow(new RuntimeException("x")).when(observer).laneChanged(any());
        String second = lanes.get("Contacted");
        send(post(crm() + "/deals/" + dealId + "/move"), managerToken, Map.of("stageId", second))
                .andExpect(status().isOk());
        assertThat(deal(dealId).get("stageId").asText()).isEqualTo(second);
    }
}
