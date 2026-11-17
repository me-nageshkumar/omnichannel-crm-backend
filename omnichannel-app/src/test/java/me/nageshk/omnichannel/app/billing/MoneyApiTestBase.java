package me.nageshk.omnichannel.app.billing;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.app.support.MutableClock;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Fixture of the money tests: the worked example of the phase 2 plan. The clock starts on 1 Dec 2026, 10:30 in
 * India. Billing is set up (supplier in Karnataka), Gold and Platinum are active, Acme (Karnataka) and Bolt
 * (Maharashtra) have billing profiles and no plan.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MoneyApiTestBase.Fixtures.class)
abstract class MoneyApiTestBase {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final Instant DEC_1 = Instant.parse("2026-12-01T05:00:00Z");

    @TestConfiguration
    static class Fixtures {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(DEC_1);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired TestUsers testUsers;
    @Autowired TestBilling billing;
    @Autowired MutableClock clock;

    User superUser;
    User amUser;
    String superToken;
    String amToken;
    Customer acme;
    Customer bolt;
    Workspace ws;
    User admin;
    String adminToken;
    User boltAdmin;
    String boltToken;

    @BeforeEach
    void setUpMoney() throws Exception {
        clock.set(DEC_1);
        testUsers.deleteAll();
        billing.reset();
        superUser = testUsers.create("money-super", "pw", BuiltInRoles.SUPER_ADMIN, null);
        amUser = testUsers.create("money-am", "pw", BuiltInRoles.ACCOUNT_MANAGER, null);
        acme = testUsers.customer("Acme");
        ws = testUsers.workspace(acme.getId(), "Main");
        admin = testUsers.createInCustomer(acme.getId(), "money-admin", BuiltInRoles.CUSTOMER_ADMIN, List.of());
        bolt = testUsers.customer("Bolt");
        testUsers.workspace(bolt.getId(), "Main");
        boltAdmin = testUsers.createInCustomer(bolt.getId(), "money-bolt", BuiltInRoles.CUSTOMER_ADMIN, List.of());
        tokens();

        PUT("/api/billing/settings", superToken, settings()).andExpect(status().isOk());
        POST("/api/billing/plans", superToken, gold()).andExpect(status().isCreated());
        POST("/api/billing/plans/gold-monthly/activate", superToken, null).andExpect(status().isOk());
        POST("/api/billing/plans", superToken, platinum()).andExpect(status().isCreated());
        POST("/api/billing/plans/platinum-monthly/activate", superToken, null).andExpect(status().isOk());
        PUT(profilePath(acme), amToken, profile("Acme Retail Pvt Ltd", "1 MG Road, Bengaluru 560001", "29")).andExpect(status().isOk());
        PUT(profilePath(bolt), amToken, profile("Bolt Traders", "9 FC Road, Pune 411004", "27")).andExpect(status().isOk());
    }

    @AfterEach
    void leaveNoMoney() {
        billing.reset();
    }

    /** Fresh tokens: needed again after the clock has moved past a token's life. */
    void tokens() {
        superToken = testUsers.accessToken(superUser);
        amToken = testUsers.accessToken(amUser);
        adminToken = testUsers.accessToken(admin);
        boltToken = testUsers.accessToken(boltAdmin);
    }

    /** Moves the clock to 10:30 in India on the day and renews the tokens. */
    void on(LocalDate day) {
        clock.set(day.atTime(10, 30).atZone(IST).toInstant());
        tokens();
    }

    static LocalDate day(int year, int month, int dayOfMonth) {
        return LocalDate.of(year, month, dayOfMonth);
    }

    static String profilePath(Customer c) {
        return "/api/billing/customers/" + c.getId() + "/billing-profile";
    }

    static String ordersPath(Customer c) {
        return "/api/billing/customers/" + c.getId() + "/orders";
    }

    static String quotesPath(Customer c) {
        return "/api/billing/customers/" + c.getId() + "/quotes";
    }

    static Map<String, Object> settings() {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("zone", "Asia/Kolkata");
        b.put("graceDays", 7);
        b.put("customersWithoutPlan", "UNRESTRICTED");
        b.put("supplierLegalName", "Omni Cloud Pvt Ltd");
        b.put("supplierAddress", "12 Residency Road, Bengaluru 560025");
        b.put("supplierStateCode", "29");
        b.put("supplierGstin", "29AAACO1234A1Z5");
        b.put("sacCode", "998314");
        b.put("paymentInstructions", "UPI omni@bank");
        return b;
    }

    static Map<String, Object> profile(String name, String address, String state) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("legalName", name);
        b.put("address", address);
        b.put("stateCode", state);
        return b;
    }

    static Map<String, Object> gold() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("code", "gold-monthly");
        p.put("name", "Gold");
        p.put("period", "MONTHLY");
        p.put("rank", 2);
        p.put("items", List.of(Map.of("groupCode", "crm", "price", 1000)));
        p.put("addOns", List.of(Map.of("groupCode", "automations", "price", 300)));
        p.put("limits", Map.of("USERS", 5));
        p.put("limitAddOns", List.of(Map.of("key", "USERS", "unitPrice", 100, "unit", 5)));
        p.put("discountPercent", 10);
        p.put("offered", true);
        return p;
    }

    static Map<String, Object> platinum() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("code", "platinum-monthly");
        p.put("name", "Platinum");
        p.put("period", "MONTHLY");
        p.put("rank", 3);
        p.put("items", List.of(Map.of("groupCode", "crm", "price", 1000), Map.of("groupCode", "automations", "price", 500)));
        p.put("discountPercent", 0);
        p.put("offered", true);
        return p;
    }

    static Map<String, Object> request(String kind, String planCode) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("kind", kind);
        if (planCode != null) b.put("planCode", planCode);
        return b;
    }

    static Map<String, Object> addOnRequest(String groupCode) {
        Map<String, Object> b = request("ADDON", null);
        b.put("addOn", Map.of("kind", "GROUP", "groupCode", groupCode));
        return b;
    }

    static Map<String, Object> usersRequest(int quantity) {
        Map<String, Object> b = request("ADDON", null);
        b.put("addOn", Map.of("kind", "LIMIT", "limitKey", "USERS", "quantity", quantity));
        return b;
    }

    /** Raises an order as the account manager and returns it. */
    JsonNode raise(Customer c, Map<String, Object> body) throws Exception {
        return json(POST(ordersPath(c), amToken, body).andExpect(status().isCreated()));
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
}
