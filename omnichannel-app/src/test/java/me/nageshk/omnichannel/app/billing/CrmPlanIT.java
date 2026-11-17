package me.nageshk.omnichannel.app.billing;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.app.support.TestBilling;
import me.nageshk.omnichannel.app.support.TestUsers;
import me.nageshk.omnichannel.common.billing.LimitKey;
import me.nageshk.omnichannel.db.tenant.Customer;
import me.nageshk.omnichannel.db.tenant.Workspace;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.security.access.BuiltInRoles;
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

/** For a customer on a plan the plan, not the per-brand switch, decides whether the CRM is on and how big it may get. */
@SpringBootTest
@AutoConfigureMockMvc
class CrmPlanIT {

    @Autowired MockMvc mvc;
    @Autowired TestUsers testUsers;
    @Autowired TestBilling billing;
    @Autowired MongoTemplate mongo;

    Customer customer;
    Workspace ws;
    String adminToken;

    @BeforeEach
    void setUp() {
        testUsers.deleteAll();
        billing.reset();
        for (String c : List.of("crmSettings", "crmPipelines")) mongo.remove(new Query(), c);
        customer = testUsers.customer("Acme");
        ws = testUsers.workspace(customer.getId(), "Main");
        User admin = testUsers.createInCustomer(customer.getId(), "crm-admin", BuiltInRoles.CUSTOMER_ADMIN, List.of());
        adminToken = testUsers.accessToken(admin);
    }

    @Test
    void withoutAPlanTheCrmIsOffUntilSwitchedOn() throws Exception {
        crm(get(path("/status"))).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        crm(get(path("/pipelines"))).andExpect(status().isPaymentRequired());
    }

    @Test
    void aPlanWithTheCrmSwitchesItOnInABrandNobodyTouched() throws Exception {
        billing.assign(customer.getId(), Set.of("crm"), Map.of(LimitKey.CRM_PIPELINES, 2));

        crm(get(path("/status"))).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.limits.maxPipelines").value(2));
        // the default pipeline was made on first use; one more fits, a third does not
        crm(get(path("/pipelines"))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        crm(post(path("/pipelines")).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Second\"}"))
                .andExpect(status().is2xxSuccessful());
        crm(post(path("/pipelines")).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Third\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LIMIT_REACHED"))
                .andExpect(jsonPath("$.key").value("CRM_PIPELINES")).andExpect(jsonPath("$.limit").value("2"));
    }

    @Test
    void aPlanWithoutTheCrmHidesItEvenIfTheBrandSwitchIsOn() throws Exception {
        billing.assign(customer.getId(), Set.of("crm"), Map.of());
        crm(get(path("/pipelines"))).andExpect(status().isOk());

        billing.assign(customer.getId(), Set.of(), Map.of());
        crm(get(path("/pipelines"))).andExpect(status().isForbidden());
    }

    private String path(String rest) {
        return "/api/workspaces/" + ws.getId() + "/crm" + rest;
    }

    private ResultActions crm(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + adminToken));
    }
}
