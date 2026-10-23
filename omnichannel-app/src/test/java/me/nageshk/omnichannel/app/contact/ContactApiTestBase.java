package me.nageshk.omnichannel.app.contact;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.app.support.TestUsers;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactAttribute;
import me.nageshk.omnichannel.db.contact.ContactAttributeRepository;
import me.nageshk.omnichannel.db.contact.ContactFilterRepository;
import me.nageshk.omnichannel.db.contact.ContactLabel;
import me.nageshk.omnichannel.db.contact.ContactLabelRepository;
import me.nageshk.omnichannel.db.contact.ContactNoteRepository;
import me.nageshk.omnichannel.db.contact.ContactRepository;
import me.nageshk.omnichannel.db.contact.ContactSource;
import me.nageshk.omnichannel.db.tenant.Customer;
import me.nageshk.omnichannel.db.tenant.Workspace;
import me.nageshk.omnichannel.db.user.WorkspaceRole;
import me.nageshk.omnichannel.security.access.BuiltInRoles;
import me.nageshk.omnichannel.service.contact.AttributeKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Test fixture shared by the contact API tests: one customer, two brands, and a token per role in brand A. */
@SpringBootTest
@AutoConfigureMockMvc
abstract class ContactApiTestBase {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired TestUsers testUsers;
    @Autowired ContactRepository contacts;
    @Autowired ContactLabelRepository labels;
    @Autowired ContactAttributeRepository attributes;
    @Autowired ContactNoteRepository notes;
    @Autowired ContactFilterRepository filters;
    @Autowired MongoTemplate mongo;

    Customer acme;
    Workspace brandA;
    Workspace brandB;
    String bossToken;
    String wmToken;
    String leadToken;
    String agentToken;
    String wmBToken;

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

    ResultActions send(MockHttpServletRequestBuilder req, String token, Object body) throws Exception {
        if (token != null) req = req.header("Authorization", "Bearer " + token);
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        return mvc.perform(req);
    }

    /** Exports brand A's contacts as CSV and returns the body. */
    protected String download(Map<String, Object> request, String token) throws Exception {
        MvcResult started = send(post(base(brandA) + "/contacts/export"), token, request)
                .andExpect(request().asyncStarted()).andReturn();
        return mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    JsonNode json(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    ContactLabel label(Workspace w, String name) {
        ContactLabel l = new ContactLabel();
        l.setWorkspaceId(w.getId());
        l.setName(name);
        l.setColor("blue");
        return labels.save(l);
    }

    ContactAttribute attribute(Workspace w, String name) {
        ContactAttribute a = new ContactAttribute();
        a.setWorkspaceId(w.getId());
        a.setName(name);
        a.setKey(AttributeKeys.generate(name,
                attributes.findByWorkspaceIdOrderByNameKey(w.getId()).stream().map(ContactAttribute::getKey)
                        .collect(java.util.stream.Collectors.toSet())));
        return attributes.save(a);
    }

    Contact contact(Workspace w, String name, String phone) {
        Contact c = new Contact();
        c.setWorkspaceId(w.getId());
        c.setCustomerId(acme.getId());
        c.setName(name);
        c.setPhone(phone);
        c.setSource(ContactSource.MANUAL);
        return contacts.save(c);
    }
}
