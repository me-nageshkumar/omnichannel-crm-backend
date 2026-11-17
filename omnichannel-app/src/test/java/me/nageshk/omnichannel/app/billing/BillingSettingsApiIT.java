package me.nageshk.omnichannel.app.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class BillingSettingsApiIT extends BillingApiTestBase {

    static final String SETTINGS = "/api/billing/settings";

    private Map<String, Object> body(String zone, int graceDays, String withoutPlan, Long version) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("zone", zone);
        b.put("graceDays", graceDays);
        b.put("customersWithoutPlan", withoutPlan);
        b.put("version", version);
        return b;
    }

    @Test
    void startsWithTheDefaults() throws Exception {
        GET(SETTINGS, amToken).andExpect(status().isOk()).andExpect(jsonPath("$.zone").value("Asia/Kolkata"))
                .andExpect(jsonPath("$.graceDays").value(7))
                .andExpect(jsonPath("$.customersWithoutPlan").value("UNRESTRICTED"));
        GET(SETTINGS, adminToken).andExpect(status().isForbidden());
    }

    @Test
    void onlyWhoMayManageBillingSettingsSavesThem() throws Exception {
        PUT(SETTINGS, amToken, body("Asia/Kolkata", 3, "UNRESTRICTED", null)).andExpect(status().isForbidden());
        JsonNode saved = json(PUT(SETTINGS, superToken, body("Asia/Kolkata", 3, "UNRESTRICTED", null))
                .andExpect(status().isOk()));
        assertThat(saved.get("graceDays").asInt()).isEqualTo(3);
        // the copy read before that save is stale
        PUT(SETTINGS, superToken, body("Asia/Kolkata", 4, "UNRESTRICTED", null)).andExpect(status().isConflict());
        PUT(SETTINGS, superToken, body("Asia/Kolkata", 4, "UNRESTRICTED", saved.get("version").asLong()))
                .andExpect(status().isOk());
    }

    @Test
    void refusesSettingsThatCannotWork() throws Exception {
        PUT(SETTINGS, superToken, body("Mars/Olympus", 7, "UNRESTRICTED", null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.zone").exists());
        PUT(SETTINGS, superToken, body("Asia/Kolkata", -1, "UNRESTRICTED", null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.graceDays").exists());
        PUT(SETTINGS, superToken, body("Asia/Kolkata", 91, "UNRESTRICTED", null)).andExpect(status().isBadRequest());
        PUT(SETTINGS, superToken, body("Asia/Kolkata", 7, null, null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.customersWithoutPlan").exists());
    }

    @Test
    void customersWithoutAPlanCanBeMadeReadOnly() throws Exception {
        assertThat(json(GET("/api/users/me", adminToken)).at("/subscription/readOnly").asBoolean()).isFalse();
        PUT(SETTINGS, superToken, body("Asia/Kolkata", 7, "READ_ONLY", null)).andExpect(status().isOk());
        JsonNode me = json(GET("/api/users/me", adminToken));
        assertThat(me.at("/subscription/readOnly").asBoolean()).isTrue();
        assertThat(me.at("/subscription/state").asString()).isEqualTo("NONE");
    }
}
