package me.nageshk.omnichannel.phone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.access.BuiltInRoles;
import me.nageshk.omnichannel.support.TestUsers;
import me.nageshk.omnichannel.tenant.Customer;
import me.nageshk.omnichannel.tenant.Workspace;
import me.nageshk.omnichannel.user.WorkspaceRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
class PhoneNumbersApiIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired TestUsers testUsers;
    @Autowired PhoneNumberRepository numbers;

    Customer acme;
    Workspace brandA;
    Workspace brandB;
    Workspace globexShop;
    String bossToken;
    String wmToken;
    String leadToken;
    String agentToken;
    String amToken;

    @BeforeEach
    void setUp() {
        testUsers.deleteAll();
        numbers.deleteAll();
        acme = testUsers.customer("Acme");
        brandA = testUsers.workspace(acme.getId(), "Brand A");
        brandB = testUsers.workspace(acme.getId(), "Brand B");
        Customer globex = testUsers.customer("Globex");
        globexShop = testUsers.workspace(globex.getId(), "Globex Shop");
        bossToken = testUsers.accessToken(
                testUsers.createInCustomer(acme.getId(), "boss", BuiltInRoles.CUSTOMER_ADMIN, List.of()));
        wmToken = token("wm", BuiltInRoles.WORKSPACE_MANAGER);
        leadToken = token("lead", BuiltInRoles.TEAM_LEAD);
        agentToken = token("agent", BuiltInRoles.AGENT);
        amToken = testUsers.accessToken(testUsers.create("am", "pw", BuiltInRoles.ACCOUNT_MANAGER, null));
    }

    @AfterEach
    void clean() {
        numbers.deleteAll();
        testUsers.deleteAll();
    }

    private String token(String username, String roleName) {
        return testUsers.accessToken(testUsers.createInCustomer(acme.getId(), username, null,
                List.of(new WorkspaceRole(brandA.getId(), testUsers.roleId(roleName)))));
    }

    private String numbersOf(Workspace w) {
        return "/api/workspaces/" + w.getId() + "/phone-numbers";
    }

    private ResultActions send(MockHttpServletRequestBuilder req, String token, Object body) throws Exception {
        if (token != null) req = req.header("Authorization", "Bearer " + token);
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        return mvc.perform(req);
    }

    private Map<String, Object> number(String number, String label) {
        Map<String, Object> body = new HashMap<>();
        body.put("number", number);
        body.put("label", label);
        return body;
    }

    private String add(Workspace w, String number, String token) throws Exception {
        JsonNode body = mapper.readTree(send(post(numbersOf(w)), token, number(number, null))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return body.get("id").asText();
    }

    @Test
    void managerAddsNumber() throws Exception {
        send(post(numbersOf(brandA)), wmToken, number("919928088743", "  Main line "))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.number").value("919928088743"))
                .andExpect(jsonPath("$.label").value("Main line"))
                .andExpect(jsonPath("$.workspaceId").value(brandA.getId()))
                .andExpect(jsonPath("$.workspaceName").value("Brand A"))
                .andExpect(jsonPath("$.customerName").value("Acme"))
                .andExpect(jsonPath("$.status").value("NOT_CONNECTED_YET"))
                .andExpect(jsonPath("$.hasDevice").value(false))
                .andExpect(jsonPath("$.disabled").isEmpty());

        send(get(numbersOf(brandA)), wmToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].number").value("919928088743"));
        send(get(numbersOf(brandB)), bossToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void invalidNumberIs400() throws Exception {
        for (String bad : List.of("+919928088743", "9928087", "0919928088743", "91992808874312345", "")) {
            send(post(numbersOf(brandA)), wmToken, number(bad, null))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION"))
                    .andExpect(jsonPath("$.fields.number").isNotEmpty());
        }
        send(post(numbersOf(brandA)), wmToken, number("919928088743", "x".repeat(61)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.label").isNotEmpty());
    }

    @Test
    void duplicateNumberAcrossBrandsIs409() throws Exception {
        add(brandA, "919928088743", wmToken);
        send(post(numbersOf(brandB)), bossToken, number("919928088743", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE"))
                .andExpect(jsonPath("$.field").value("number"));
    }

    @Test
    void teamLeadCanViewNotManage() throws Exception {
        String id = add(brandA, "919928088743", wmToken);
        send(get(numbersOf(brandA)), leadToken, null).andExpect(status().isOk());
        send(post(numbersOf(brandA)), leadToken, number("919900000001", null)).andExpect(status().isForbidden());
        send(post(numbersOf(brandA) + "/" + id + "/disable"), leadToken, null).andExpect(status().isForbidden());
    }

    @Test
    void agentCannotView() throws Exception {
        send(get(numbersOf(brandA)), agentToken, null).andExpect(status().isForbidden());
    }

    @Test
    void otherBrandsNumberIs404() throws Exception {
        String id = add(brandB, "919928088743", bossToken);
        send(put(numbersOf(brandA) + "/" + id), wmToken, Map.of("label", "x")).andExpect(status().isNotFound());
        send(post(numbersOf(brandA) + "/" + id + "/disable"), bossToken, null).andExpect(status().isNotFound());
        send(get(numbersOf(globexShop)), bossToken, null).andExpect(status().isNotFound());
    }

    @Test
    void managerRelabelsAndDisablesAndEnables() throws Exception {
        String id = add(brandA, "919928088743", wmToken);
        send(put(numbersOf(brandA) + "/" + id), wmToken, Map.of("label", "Sales"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Sales"));
        send(post(numbersOf(brandA) + "/" + id + "/disable"), wmToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"))
                .andExpect(jsonPath("$.disabledBecause").value("NUMBER"))
                .andExpect(jsonPath("$.disabled.level").value("BRAND"));
        send(post(numbersOf(brandA) + "/" + id + "/enable"), wmToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_CONNECTED_YET"))
                .andExpect(jsonPath("$.disabled").isEmpty());
    }

    @Test
    void managerCannotEnableNumberAdminDisabled() throws Exception {
        String id = add(brandA, "919928088743", wmToken);
        send(post(numbersOf(brandA) + "/" + id + "/disable"), bossToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disabled.level").value("COMPANY"));
        send(post(numbersOf(brandA) + "/" + id + "/enable"), wmToken, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DISABLED_AT_HIGHER_LEVEL"))
                .andExpect(jsonPath("$.level").value("COMPANY"));
    }

    @Test
    void accountManagerSeesAllAndSearches() throws Exception {
        add(brandA, "919928088743", wmToken);
        add(brandB, "919900000001", bossToken);
        send(get("/api/phone-numbers"), amToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.items.length()").value(2));
        send(get("/api/phone-numbers").param("q", "88743"), amToken, null)
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].workspaceName").value("Brand A"));
        send(get("/api/phone-numbers").param("q", "brand b"), amToken, null)
                .andExpect(jsonPath("$.items[0].number").value("919900000001"));
        send(get("/api/phone-numbers").param("q", "acme"), amToken, null)
                .andExpect(jsonPath("$.total").value(2));
        send(get("/api/phone-numbers").param("status", "DISABLED"), amToken, null)
                .andExpect(jsonPath("$.total").value(0));
        send(get("/api/phone-numbers"), bossToken, null).andExpect(status().isForbidden());
    }

    @Test
    void accountManagerDisableCannotBeLiftedByManager() throws Exception {
        String id = add(brandA, "919928088743", wmToken);
        send(post("/api/phone-numbers/" + id + "/disable"), amToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disabled.level").value("PLATFORM"));
        send(post(numbersOf(brandA) + "/" + id + "/enable"), wmToken, null)
                .andExpect(status().isForbidden());
        send(post(numbersOf(brandA) + "/" + id + "/enable"), bossToken, null)
                .andExpect(status().isForbidden());
        send(get("/api/phone-numbers").param("status", "DISABLED"), amToken, null)
                .andExpect(jsonPath("$.total").value(1));
        send(post("/api/phone-numbers/" + id + "/enable"), amToken, null).andExpect(status().isOk());
    }

    @Test
    void platformStaffCannotAdd() throws Exception {
        send(post(numbersOf(brandA)), amToken, number("919928088743", null)).andExpect(status().isNotFound());
        send(post("/api/phone-numbers"), amToken, number("919928088743", null))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(404, 405));
        assertThat(numbers.count()).isZero();
    }

    @Test
    void deleteRemovesNumber() throws Exception {
        String a = add(brandA, "919928088743", wmToken);
        String b = add(brandB, "919900000001", bossToken);
        send(delete(numbersOf(brandA) + "/" + a), wmToken, null).andExpect(status().isNoContent());
        send(delete("/api/phone-numbers/" + b), amToken, null).andExpect(status().isNoContent());
        assertThat(numbers.count()).isZero();
        send(delete(numbersOf(brandA) + "/" + a), wmToken, null).andExpect(status().isNotFound());
    }

    @Test
    void managerCannotDeleteNumberDisabledAbove() throws Exception {
        String id = add(brandA, "919928088743", wmToken);
        send(post("/api/phone-numbers/" + id + "/disable"), amToken, null).andExpect(status().isOk());
        send(delete(numbersOf(brandA) + "/" + id), wmToken, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DISABLED_AT_HIGHER_LEVEL"));
        send(delete(numbersOf(brandA) + "/" + id), bossToken, null).andExpect(status().isForbidden());
        assertThat(numbers.count()).isEqualTo(1);
        send(delete("/api/phone-numbers/" + id), amToken, null).andExpect(status().isNoContent());
    }

    @Test
    void managerMayDeleteNumberTheyDisabled() throws Exception {
        String id = add(brandA, "919928088743", wmToken);
        send(post(numbersOf(brandA) + "/" + id + "/disable"), wmToken, null).andExpect(status().isOk());
        send(delete(numbersOf(brandA) + "/" + id), wmToken, null).andExpect(status().isNoContent());
    }
}
