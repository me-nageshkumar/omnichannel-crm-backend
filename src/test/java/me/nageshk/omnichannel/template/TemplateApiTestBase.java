package me.nageshk.omnichannel.template;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.access.BuiltInRoles;
import me.nageshk.omnichannel.contact.ContactAttributeRepository;
import me.nageshk.omnichannel.contact.ContactRepository;
import me.nageshk.omnichannel.support.TestUsers;
import me.nageshk.omnichannel.tenant.Customer;
import me.nageshk.omnichannel.tenant.Workspace;
import me.nageshk.omnichannel.user.WorkspaceRole;
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

/** Test fixture shared by the template API tests: one customer, two brands, and a token per role in brand A. */
@SpringBootTest
@AutoConfigureMockMvc
abstract class TemplateApiTestBase {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired TestUsers testUsers;
    @Autowired MessageTemplateRepository templates;
    @Autowired ContactRepository contacts;
    @Autowired ContactAttributeRepository attributes;

    Customer acme;
    Workspace brandA;
    Workspace brandB;
    String bossToken;
    String wmToken;
    String leadToken;
    String agentToken;
    String wmBToken;
    String accountManagerToken;

    @BeforeEach
    void setUpBrands() {
        testUsers.deleteAll();
        acme = testUsers.customer("Acme");
        brandA = testUsers.workspace(acme.getId(), "Brand A");
        brandB = testUsers.workspace(acme.getId(), "Brand B");
        bossToken = testUsers.accessToken(
                testUsers.createInCustomer(acme.getId(), "boss", BuiltInRoles.CUSTOMER_ADMIN, List.of()));
        wmToken = brandToken("wm", BuiltInRoles.WORKSPACE_MANAGER, brandA);
        leadToken = brandToken("lead", BuiltInRoles.WORKSPACE_MANAGER, brandA);
        agentToken = brandToken("agent", BuiltInRoles.AGENT, brandA);
        wmBToken = brandToken("wmb", BuiltInRoles.WORKSPACE_MANAGER, brandB);
        accountManagerToken = testUsers.tokenFor("accountmgr");
    }

    @AfterEach
    void cleanUp() {
        testUsers.deleteAll();
    }

    String brandToken(String username, String roleName, Workspace brand) {
        return testUsers.accessToken(testUsers.createInCustomer(acme.getId(), username, null,
                List.of(new WorkspaceRole(brand.getId(), testUsers.roleId(roleName)))));
    }

    static String base(Workspace w) {
        return "/api/workspaces/" + w.getId();
    }

    String url() {
        return base(brandA) + "/templates";
    }

    ResultActions send(MockHttpServletRequestBuilder req, String token, Object body) throws Exception {
        if (token != null) req = req.header("Authorization", "Bearer " + token);
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        return mvc.perform(req);
    }

    JsonNode json(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    /** A placeholder settings entry; null fields are omitted. */
    static Map<String, Object> setting(String name, String fallback, String label, String example, Boolean required) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        if (fallback != null) m.put("fallback", fallback);
        if (label != null) m.put("label", label);
        if (example != null) m.put("example", example);
        if (required != null) m.put("required", required);
        return m;
    }

    /** A TEXT template request with the given body and placeholder settings. */
    static Map<String, Object> text(String name, String body, Map<String, Object>... settings) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("name", name);
        r.put("type", "TEXT");
        r.put("content", Map.of("body", body));
        r.put("placeholders", new ArrayList<>(List.of(settings)));
        return r;
    }
}
