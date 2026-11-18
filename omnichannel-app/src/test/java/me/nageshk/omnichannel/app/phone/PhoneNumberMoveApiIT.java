package me.nageshk.omnichannel.app.phone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.app.support.TestDevices;
import me.nageshk.omnichannel.app.support.TestUsers;
import me.nageshk.omnichannel.db.access.Role;
import me.nageshk.omnichannel.db.access.RoleLevel;
import me.nageshk.omnichannel.db.access.RoleRepository;
import me.nageshk.omnichannel.db.phone.PhoneNumber;
import me.nageshk.omnichannel.db.tenant.Customer;
import me.nageshk.omnichannel.db.tenant.Workspace;
import me.nageshk.omnichannel.db.user.WorkspaceRole;
import me.nageshk.omnichannel.security.access.BuiltInRoles;
import me.nageshk.omnichannel.security.access.RoleCache;
import me.nageshk.omnichannel.service.phone.move.PhoneNumberMove;
import me.nageshk.omnichannel.service.phone.move.PhoneNumberMoveRepository;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The platform's move endpoints (spec §7.2): who may move, the record they leave, the preview's refusals. */
@SpringBootTest
@AutoConfigureMockMvc
class PhoneNumberMoveApiIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired TestUsers testUsers;
    @Autowired TestDevices testDevices;
    @Autowired MongoTemplate mongo;
    @Autowired RoleRepository roles;
    @Autowired RoleCache roleCache;
    @Autowired PhoneNumberMoveRepository moves;

    Workspace brandA;
    Workspace brandB;
    Workspace globexShop;
    PhoneNumber number;
    String rootToken;
    String wmToken;
    String amToken;

    @BeforeEach
    void setUp() {
        clean();
        Customer acme = testUsers.customer("Acme");
        brandA = testUsers.workspace(acme.getId(), "Brand A");
        brandB = testUsers.workspace(acme.getId(), "Brand B");
        globexShop = testUsers.workspace(testUsers.customer("Globex").getId(), "Globex Shop");
        number = testDevices.number(brandA, "919900000777");
        rootToken = testUsers.accessToken(testUsers.create("root", "pw", BuiltInRoles.SUPER_ADMIN, null));
        // platform staff who may see every number but not manage them: no built-in role is that, so make one
        Role viewer = new Role();
        viewer.setName("Number viewer");
        viewer.setLevel(RoleLevel.PLATFORM);
        viewer.setAuthorities(Set.of("phone-number:view-all"));
        roles.save(viewer);
        roleCache.evictAll();
        amToken = testUsers.accessToken(testUsers.create("am", "pw", "Number viewer", null));
        wmToken = testUsers.accessToken(testUsers.createInCustomer(acme.getId(), "wm", null,
                List.of(new WorkspaceRole(brandA.getId(), testUsers.roleId(BuiltInRoles.WORKSPACE_MANAGER)))));
    }

    @AfterEach
    void clean() {
        mongo.remove(new Query(), "phoneNumbers");
        mongo.remove(new Query(), "phoneNumberMoves");
        testUsers.deleteAll();
    }

    private String base() {
        return "/api/phone-numbers/" + number.getId() + "/move";
    }

    private ResultActions send(MockHttpServletRequestBuilder req, String token, Object body) throws Exception {
        req = req.header("Authorization", "Bearer " + token);
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        return mvc.perform(req);
    }

    @Test
    void superAdminMovesAndSeesTheRecord() throws Exception {
        send(get(base() + "/preview").param("workspaceId", brandB.getId()), rootToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromWorkspaceName").value("Brand A"))
                .andExpect(jsonPath("$.toWorkspaceName").value("Brand B"));
        send(get(base()), rootToken, null).andExpect(status().isNotFound());

        send(post(base()), rootToken, Map.of("workspaceId", brandB.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phoneNumberId").value(number.getId()))
                .andExpect(jsonPath("$.fromWorkspaceId").value(brandA.getId()))
                .andExpect(jsonPath("$.fromWorkspaceName").value("Brand A"))
                .andExpect(jsonPath("$.toWorkspaceId").value(brandB.getId()))
                .andExpect(jsonPath("$.toWorkspaceName").value("Brand B"))
                .andExpect(jsonPath("$.step").value("DONE"))
                .andExpect(jsonPath("$.done").value(true))
                .andExpect(jsonPath("$.error").isEmpty())
                .andExpect(jsonPath("$.startedAt").isNotEmpty())
                .andExpect(jsonPath("$.finishedAt").isNotEmpty());

        send(get(base()), rootToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.done").value(true))
                .andExpect(jsonPath("$.counts.chats").value(0));
        send(post(base() + "/retry"), rootToken, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("number_move_nothing_to_retry"));
    }

    @Test
    void managerOfTheBrandCannotMove() throws Exception {
        send(post(base()), wmToken, Map.of("workspaceId", brandB.getId())).andExpect(status().isForbidden());
        send(get(base() + "/preview").param("workspaceId", brandB.getId()), wmToken, null)
                .andExpect(status().isForbidden());
        send(get(base()), wmToken, null).andExpect(status().isForbidden());
        send(post(base() + "/retry"), wmToken, null).andExpect(status().isForbidden());
    }

    @Test
    void accountManagerWithViewAllSeesButCannotMove() throws Exception {
        send(post(base()), rootToken, Map.of("workspaceId", brandB.getId())).andExpect(status().isOk());

        send(get(base()), amToken, null).andExpect(status().isOk()).andExpect(jsonPath("$.done").value(true));
        send(post(base()), amToken, Map.of("workspaceId", brandA.getId())).andExpect(status().isForbidden());
        send(post(base() + "/retry"), amToken, null).andExpect(status().isForbidden());
        send(get(base() + "/preview").param("workspaceId", brandA.getId()), amToken, null)
                .andExpect(status().isForbidden());
    }

    @Test
    void previewNeedsWorkspaceOfSameCustomer() throws Exception {
        send(get(base() + "/preview").param("workspaceId", globexShop.getId()), rootToken, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("number_move_other_customer"));
    }

    @Test
    void searchShowsMoveUnfinished() throws Exception {
        PhoneNumber quiet = testDevices.number(brandB, "919900000778");
        PhoneNumberMove m = new PhoneNumberMove();
        m.setCustomerId(number.getCustomerId());
        m.setPhoneNumberId(number.getId());
        m.setUnfinishedOf(number.getId());
        m.setFromWorkspaceId(brandA.getId());
        m.setToWorkspaceId(brandB.getId());
        m.setStep(PhoneNumberMove.Step.STARTED);
        m.setStartedAt(Instant.now());
        moves.insert(m);

        JsonNode page = mapper.readTree(send(get("/api/phone-numbers"), rootToken, null)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        boolean sawUnfinished = false;
        for (JsonNode item : page.get("items")) {
            boolean unfinished = item.get("moveUnfinished").asBoolean();
            if (item.get("id").asString().equals(number.getId())) {
                assertThat(unfinished).isTrue();
                sawUnfinished = true;
            } else if (item.get("id").asString().equals(quiet.getId())) {
                assertThat(unfinished).isFalse();
            }
        }
        assertThat(sawUnfinished).isTrue();
        send(get(base()), rootToken, null).andExpect(status().isOk()).andExpect(jsonPath("$.done").value(false));
    }
}
