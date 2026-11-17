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

    private Map<String, Object> full() {
        Map<String, Object> b = body("Asia/Kolkata", 7, "UNRESTRICTED", null);
        b.put("supplierLegalName", "Omni Cloud Pvt Ltd");
        b.put("supplierAddress", "12 Residency Road, Bengaluru 560025");
        b.put("supplierStateCode", "29");
        b.put("supplierGstin", "29AAACO1234A1Z5");
        b.put("sacCode", "998314");
        b.put("gstRatePercent", 18);
        b.put("invoicePrefix", "INV");
        b.put("creditNotePrefix", "CN");
        b.put("quoteValidDays", 7);
        b.put("renewalWindowDays", 30);
        b.put("paymentInstructions", "UPI omni@bank, or NEFT to account 1234");
        return b;
    }

    @Test
    void theSupplierDetailsMakeBillingComplete() throws Exception {
        GET(SETTINGS, amToken).andExpect(jsonPath("$.complete").value(false))
                .andExpect(jsonPath("$.gstRatePercent").value(18)).andExpect(jsonPath("$.invoicePrefix").value("INV"))
                .andExpect(jsonPath("$.quoteValidDays").value(7)).andExpect(jsonPath("$.renewalWindowDays").value(30));
        JsonNode saved = json(PUT(SETTINGS, superToken, full()).andExpect(status().isOk()));
        assertThat(saved.get("complete").asBoolean()).isTrue();
        assertThat(saved.get("supplierLegalName").asString()).isEqualTo("Omni Cloud Pvt Ltd");
        assertThat(saved.get("supplierStateCode").asString()).isEqualTo("29");
        assertThat(saved.get("paymentInstructions").asString()).contains("UPI");
        // a save that names only the first settings keeps the rest
        PUT(SETTINGS, superToken, body("Asia/Kolkata", 5, "UNRESTRICTED", saved.get("version").asLong()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.complete").value(true))
                .andExpect(jsonPath("$.supplierGstin").value("29AAACO1234A1Z5"));
    }

    @Test
    void refusesSupplierAndInvoiceSettingsThatCannotWork() throws Exception {
        String[][] bad = {
                {"supplierStateCode", "99"}, {"supplierGstin", "27AAACO1234A1Z5"}, {"supplierGstin", "junk"},
                {"invoicePrefix", "inv 1"}, {"creditNotePrefix", ""}, {"paymentInstructions", "x".repeat(1001)}};
        for (String[] f : bad) {
            Map<String, Object> b = full();
            b.put(f[0], f[1]);
            PUT(SETTINGS, superToken, b).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields." + f[0]).exists());
        }
        Object[][] numbers = {{"gstRatePercent", 101}, {"gstRatePercent", 18.123}, {"quoteValidDays", 0},
                {"quoteValidDays", 61}, {"renewalWindowDays", -1}, {"renewalWindowDays", 121}};
        for (Object[] f : numbers) {
            Map<String, Object> b = full();
            b.put((String) f[0], f[1]);
            PUT(SETTINGS, superToken, b).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fields." + f[0]).exists());
        }
    }

    @Test
    void listsTheStates() throws Exception {
        JsonNode states = json(GET("/api/billing/states", amToken).andExpect(status().isOk()));
        assertThat(states.size()).isGreaterThan(35);
        boolean karnataka = false;
        for (JsonNode s : states) karnataka |= s.get("code").asString().equals("29") && s.get("name").asString().equals("Karnataka");
        assertThat(karnataka).isTrue();
        GET("/api/billing/states", adminToken).andExpect(status().isForbidden());
    }
}
