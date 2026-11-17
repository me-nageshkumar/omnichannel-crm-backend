package me.nageshk.omnichannel.app.billing;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.app.support.TestBilling;
import me.nageshk.omnichannel.app.support.TestUsers;
import me.nageshk.omnichannel.db.tenant.Customer;
import me.nageshk.omnichannel.db.tenant.Workspace;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.security.access.BuiltInRoles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Fixture of the billing API tests: a Super Admin, an account manager, and a customer with one brand and its admin. */
@SpringBootTest
@AutoConfigureMockMvc
abstract class BillingApiTestBase {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired TestUsers testUsers;
    @Autowired TestBilling billing;

    String superToken;
    String amToken;
    Customer customer;
    Workspace ws;
    User admin;
    String adminToken;

    @BeforeEach
    void setUpBilling() {
        testUsers.deleteAll();
        billing.reset();
        superToken = testUsers.accessToken(testUsers.create("bill-super", "pw", BuiltInRoles.SUPER_ADMIN, null));
        amToken = testUsers.accessToken(testUsers.create("bill-am", "pw", BuiltInRoles.ACCOUNT_MANAGER, null));
        customer = testUsers.customer("Acme");
        ws = testUsers.workspace(customer.getId(), "Main");
        admin = testUsers.createInCustomer(customer.getId(), "bill-admin", BuiltInRoles.CUSTOMER_ADMIN, List.of());
        adminToken = testUsers.accessToken(admin);
    }

    @AfterEach
    void leaveNoBilling() {
        billing.reset();
    }

    ResultActions GET(String path, String token) throws Exception {
        return mvc.perform(get(path).header("Authorization", "Bearer " + token));
    }

    ResultActions POST(String path, String token, Object body) throws Exception {
        return send(post(path), token, body);
    }

    ResultActions PUT(String path, String token, Object body) throws Exception {
        return send(put(path), token, body);
    }

    ResultActions DELETE(String path, String token) throws Exception {
        return mvc.perform(delete(path).header("Authorization", "Bearer " + token));
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String token, Object body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        return mvc.perform(request);
    }

    JsonNode json(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asString()));
        return out;
    }

    /** A plan request with the CRM included at 1,000 and automations offered as an add-on at 300. */
    static Map<String, Object> planBody(String code) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("code", code);
        p.put("name", "Gold");
        p.put("description", "");
        p.put("period", "MONTHLY");
        p.put("rank", 2);
        p.put("items", List.of(Map.of("groupCode", "crm", "price", 1000)));
        p.put("addOns", List.of(Map.of("groupCode", "automations", "price", 300)));
        p.put("limits", Map.of("USERS", 5));
        p.put("limitAddOns", List.of(Map.of("key", "USERS", "unitPrice", 100, "unit", 5)));
        p.put("discountPercent", 10);
        p.put("rates", Map.of("WHATSAPP_MESSAGE", 0.05));
        p.put("offered", true);
        return p;
    }
}
