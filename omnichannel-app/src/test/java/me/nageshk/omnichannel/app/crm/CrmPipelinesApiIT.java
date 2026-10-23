package me.nageshk.omnichannel.app.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import me.nageshk.omnichannel.crm.pipeline.CrmPipeline;
import me.nageshk.omnichannel.crm.settings.CrmSettings;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import tools.jackson.databind.JsonNode;

class CrmPipelinesApiIT extends CrmTestBase {

    private JsonNode list() throws Exception {
        return json(send(get(crm() + "/pipelines"), managerToken, null).andExpect(status().isOk()));
    }

    private List<Map<String, Object>> lanes(String... names) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", names[i]);
            m.put("type", i == names.length - 1 ? "LOST" : i == names.length - 2 ? "WON" : "OPEN");
            m.put("probability", i * 10);
            out.add(m);
        }
        return out;
    }

    private JsonNode create(String name, List<Map<String, Object>> stages) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        if (stages != null) body.put("stages", stages);
        return json(send(post(crm() + "/pipelines"), managerToken, body).andExpect(status().is2xxSuccessful()));
    }

    private CrmSettings storedSettings() {
        return mongo.findOne(new Query(Criteria.where("workspaceId").is(brand.getId())), CrmSettings.class);
    }

    @Test
    void defaultSalesPipelineCreatedOnFirstAccess() throws Exception {
        enableCrm();
        JsonNode v = list();
        assertThat(v).hasSize(1);
        assertThat(v.get(0).get("name").asText()).isEqualTo("Sales");
        assertThat(v.get(0).get("isDefault").asBoolean()).isTrue();
        assertThat(v.get(0).get("stages")).hasSize(7);
        assertThat(v.get(0).get("stages").get(1).get("position").asDouble()).isEqualTo(2048.0);
        assertThat(storedSettings().getDefaultPipelineId()).isEqualTo(v.get(0).get("id").asText());
        assertThat(list()).hasSize(1);
    }

    @Test
    void concurrentFirstAccessCreatesOneSalesPipeline() throws Exception {
        enableCrm();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                Callable<Integer> c = () -> send(get(crm() + "/pipelines"), managerToken, null)
                        .andReturn().getResponse().getStatus();
                calls.add(pool.submit(c));
            }
            for (Future<Integer> f : calls) assertThat(f.get()).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }
        assertThat(mongo.count(new Query(Criteria.where("workspaceId").is(brand.getId())), CrmPipeline.class))
                .isEqualTo(1);
    }

    @Test
    void managerCreatesPipelineWithEightLanes() throws Exception {
        enableCrm();
        JsonNode v = create("Partners", lanes("A", "B", "C", "D", "E", "F", "Won", "Lost"));
        assertThat(v.get("stages")).hasSize(8);
        assertThat(v.get("isDefault").asBoolean()).isFalse();
        assertThat(v.get("stages").get(7).get("position").asDouble()).isEqualTo(8192.0);
        assertThat(v.get("stages").get(0).get("id").asText()).isNotBlank();
        assertThat(list()).hasSize(2);
        assertThat(list().get(0).get("name").asText()).isEqualTo("Partners");
        JsonNode plain = create("Plain", null);
        assertThat(plain.get("stages")).hasSize(7);
    }

    @Test
    void limitReachedAtMaxPipelines() throws Exception {
        enableCrm(1, null);
        list();
        send(post(crm() + "/pipelines"), managerToken, Map.of("name", "Second")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LIMIT_REACHED")).andExpect(jsonPath("$.limit").value("1"));
    }

    @Test
    void agentCannotCreate() throws Exception {
        enableCrm();
        send(post(crm() + "/pipelines"), agentToken, Map.of("name", "X")).andExpect(status().isForbidden());
    }

    @Test
    void makingAnotherDefaultMovesTheFlagAndSettings() throws Exception {
        enableCrm();
        JsonNode sales = list().get(0);
        JsonNode second = create("Second", null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Second");
        body.put("isDefault", true);
        body.put("version", second.get("version").asLong());
        JsonNode v = json(send(put(crm() + "/pipelines/" + second.get("id").asText()), managerToken, body)
                .andExpect(status().isOk()));
        assertThat(v.get("isDefault").asBoolean()).isTrue();
        assertThat(storedSettings().getDefaultPipelineId()).isEqualTo(second.get("id").asText());
        JsonNode old = json(send(get(crm() + "/pipelines/" + sales.get("id").asText()), managerToken, null)
                .andExpect(status().isOk()));
        assertThat(old.get("isDefault").asBoolean()).isFalse();
    }

    @Test
    void deactivatingTheDefaultIs400() throws Exception {
        enableCrm();
        JsonNode sales = list().get(0);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Sales");
        body.put("active", false);
        body.put("version", sales.get("version").asLong());
        send(put(crm() + "/pipelines/" + sales.get("id").asText()), managerToken, body)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.fields.active").exists());
    }

    @Test
    void deletingDefaultPromotesAnother() throws Exception {
        enableCrm();
        JsonNode sales = list().get(0);
        JsonNode second = create("Second", null);
        send(delete(crm() + "/pipelines/" + sales.get("id").asText()), managerToken, null)
                .andExpect(status().isNoContent());
        JsonNode left = list();
        assertThat(left).hasSize(1);
        assertThat(left.get(0).get("id").asText()).isEqualTo(second.get("id").asText());
        assertThat(left.get(0).get("isDefault").asBoolean()).isTrue();
        assertThat(storedSettings().getDefaultPipelineId()).isEqualTo(second.get("id").asText());
        send(delete(crm() + "/pipelines/" + second.get("id").asText()), managerToken, null)
                .andExpect(status().isNoContent());
        assertThat(storedSettings().getDefaultPipelineId()).isNull();
        // the next request seeds Sales again
        assertThat(list()).hasSize(1);
        assertThat(list().get(0).get("name").asText()).isEqualTo("Sales");
    }

    @Test
    void replacingStagesRenumbersAndKeepsIds() throws Exception {
        enableCrm();
        JsonNode sales = list().get(0);
        List<Map<String, Object>> stages = new ArrayList<>();
        for (JsonNode s : sales.get("stages")) {
            stages.add(new LinkedHashMap<>(mapper.convertValue(s,
                    new tools.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {})));
        }
        java.util.Collections.swap(stages, 0, 1);
        Map<String, Object> body = Map.of("version", sales.get("version").asLong(), "stages", stages);
        JsonNode v = json(send(put(crm() + "/pipelines/" + sales.get("id").asText() + "/stages"), managerToken, body)
                .andExpect(status().isOk()));
        assertThat(v.get("stages").get(0).get("name").asText()).isEqualTo("Contacted");
        assertThat(v.get("stages").get(0).get("id").asText()).isEqualTo(sales.get("stages").get(1).get("id").asText());
        assertThat(v.get("stages").get(0).get("position").asDouble()).isEqualTo(1024.0);
        assertThat(v.get("version").asLong()).isEqualTo(sales.get("version").asLong() + 1);
    }

    @Test
    void staleVersionOnStagesIs409() throws Exception {
        enableCrm();
        JsonNode sales = list().get(0);
        Map<String, Object> body = Map.of("version", sales.get("version").asLong() + 5, "stages", lanes("A", "Won", "Lost"));
        send(put(crm() + "/pipelines/" + sales.get("id").asText() + "/stages"), managerToken, body)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    @Test
    void agentSeesOnlyAssignedPipelines() throws Exception {
        enableCrm();
        list();
        send(get(crm() + "/pipelines"), agentToken, null).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        String id = list().get(0).get("id").asText();
        send(get(crm() + "/pipelines/" + id), agentToken, null).andExpect(status().isNotFound());
    }
}
