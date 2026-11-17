package me.nageshk.omnichannel.app.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;
import me.nageshk.omnichannel.db.tenant.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class BillingProfileApiIT extends BillingApiTestBase {

    @Autowired CustomerRepository customers;

    private String path() {
        return "/api/billing/customers/" + customer.getId() + "/billing-profile";
    }

    private static Map<String, Object> profile(String gstin) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("legalName", "Acme Retail Pvt Ltd");
        b.put("address", "1 MG Road, Bengaluru 560001");
        b.put("stateCode", "29");
        b.put("gstin", gstin);
        return b;
    }

    @Test
    void aNewCustomerHasNoProfileUntilStaffFillItIn() throws Exception {
        GET(path(), amToken).andExpect(status().isOk()).andExpect(jsonPath("$.complete").value(false))
                .andExpect(jsonPath("$.legalName").isEmpty());

        PUT(path(), amToken, profile("29ABCDE1234F1Z5")).andExpect(status().isOk())
                .andExpect(jsonPath("$.complete").value(true)).andExpect(jsonPath("$.gstin").value("29ABCDE1234F1Z5"));

        assertThat(customers.findById(customer.getId()).orElseThrow().getBilling().legalName()).isEqualTo("Acme Retail Pvt Ltd");
        GET(path(), amToken).andExpect(jsonPath("$.stateCode").value("29"));
        assertThat(json(GET("/api/billing/customers/" + customer.getId() + "/subscription/events", amToken)).get(0)
                .get("kind").asString()).isEqualTo("PROFILE");
        // the GSTIN is optional
        PUT(path(), amToken, profile(null)).andExpect(status().isOk()).andExpect(jsonPath("$.gstin").isEmpty());
    }

    @Test
    void refusesAProfileThatCannotGoOnAnInvoice() throws Exception {
        PUT(path(), amToken, profile("27ABCDE1234F1Z5")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.gstin").value("does not belong to the chosen state"));
        Map<String, Object> noName = profile(null);
        noName.put("legalName", " ");
        PUT(path(), amToken, noName).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.legalName").exists());
        Map<String, Object> badState = profile(null);
        badState.put("stateCode", "99");
        PUT(path(), amToken, badState).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.stateCode").exists());
        Map<String, Object> noAddress = profile(null);
        noAddress.put("address", "");
        PUT(path(), amToken, noAddress).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.address").exists());
    }

    @Test
    void onlyStaffUseTheStaffPath() throws Exception {
        GET(path(), adminToken).andExpect(status().isForbidden());
        PUT(path(), adminToken, profile(null)).andExpect(status().isForbidden());
        PUT("/api/billing/customers/000000000000000000000000/billing-profile", amToken, profile(null))
                .andExpect(status().isNotFound());
    }
}
