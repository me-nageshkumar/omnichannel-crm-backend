package me.nageshk.omnichannel.tenant;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import me.nageshk.omnichannel.access.BuiltInRoles;
import me.nageshk.omnichannel.support.TestUsers;
import me.nageshk.omnichannel.user.User;
import me.nageshk.omnichannel.user.WorkspaceRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Pins tenant isolation: every brand endpoint answers 404 (never 403, never data) to a caller who has no
 * access to the workspace, whichever way they lack it. Add new brand endpoints to {@link #brandEndpoints()}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class IsolationIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired TestUsers testUsers;
    @Autowired TeamRepository teams;

    Customer acme;
    Workspace brandA;
    Workspace brandB;
    Customer globex;
    Workspace globexShop;
    User boss;
    User wmOfB;
    User globexAgent;
    Team globexTeam;
    Team acmeTeam;
    String roleId;

    @BeforeEach
    void setUp() {
        testUsers.deleteAll();
        acme = testUsers.customer("Acme");
        brandA = testUsers.workspace(acme.getId(), "Brand A");
        brandB = testUsers.workspace(acme.getId(), "Brand B");
        globex = testUsers.customer("Globex");
        globexShop = testUsers.workspace(globex.getId(), "Globex Shop");
        boss = testUsers.createInCustomer(acme.getId(), "boss", BuiltInRoles.CUSTOMER_ADMIN, List.of());
        wmOfB = testUsers.createInCustomer(acme.getId(), "wmb", null,
                List.of(new WorkspaceRole(brandB.getId(), testUsers.roleId(BuiltInRoles.WORKSPACE_MANAGER))));
        globexAgent = testUsers.createInCustomer(globex.getId(), "gagent", null,
                List.of(new WorkspaceRole(globexShop.getId(), testUsers.roleId(BuiltInRoles.AGENT))));
        testUsers.createInCustomer(globex.getId(), "gboss", BuiltInRoles.CUSTOMER_ADMIN, List.of());
        roleId = testUsers.roleId(BuiltInRoles.AGENT);
        globexTeam = team(globexShop);
        acmeTeam = team(brandA);
    }

    private Team team(Workspace w) {
        Team t = new Team();
        t.setCustomerId(w.getCustomerId());
        t.setWorkspaceId(w.getId());
        t.setName("General");
        t.setMemberIds(new ArrayList<>());
        return teams.save(t);
    }

    /** Method, path template ({ws}, {user}, {team}) and a valid-looking body template ({roleId}). */
    static Stream<Arguments> brandEndpoints() {
        String newUser = """
                {"name":"New Person","username":"newperson","email":"n@example.com","mobileNumber":"+911234567890",
                 "temporaryPassword":"temporary-pass-1","roleId":"{roleId}"}""";
        return Stream.of(
                Arguments.of(HttpMethod.GET, "/api/workspaces/{ws}/users", null),
                Arguments.of(HttpMethod.POST, "/api/workspaces/{ws}/users", newUser),
                Arguments.of(HttpMethod.PUT, "/api/workspaces/{ws}/users/{user}",
                        "{\"name\":\"Renamed\",\"email\":\"r@example.com\",\"mobileNumber\":\"+911234567890\",\"roleId\":\"{roleId}\"}"),
                Arguments.of(HttpMethod.DELETE, "/api/workspaces/{ws}/users/{user}", null),
                Arguments.of(HttpMethod.POST, "/api/workspaces/{ws}/users/{user}/reset-password",
                        "{\"temporaryPassword\":\"another-temp-pass-1\"}"),
                Arguments.of(HttpMethod.GET, "/api/workspaces/{ws}/assignable-roles", null),
                Arguments.of(HttpMethod.GET, "/api/workspaces/{ws}/teams", null),
                Arguments.of(HttpMethod.POST, "/api/workspaces/{ws}/teams", "{\"name\":\"Support\"}"),
                Arguments.of(HttpMethod.PUT, "/api/workspaces/{ws}/teams/{team}",
                        "{\"name\":\"Support\",\"memberIds\":[]}"),
                Arguments.of(HttpMethod.PUT, "/api/workspaces/{ws}", "{\"name\":\"Renamed brand\"}"));
    }

    private ResultActions call(HttpMethod method, String path, String body, Workspace ws, User user, Team team,
            String token) throws Exception {
        String url = path.replace("{ws}", ws.getId())
                .replace("{user}", user.getId())
                .replace("{team}", team.getId());
        MockHttpServletRequestBuilder req = MockMvcRequestBuilders.request(method, url)
                .header("Authorization", "Bearer " + token);
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).content(body.replace("{roleId}", roleId));
        return mvc.perform(req);
    }

    private void expectNotFound(ResultActions result) throws Exception {
        result.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("brandEndpoints")
    void otherCustomersWorkspaceIs404ForCustomerAdmin(HttpMethod method, String path, String body) throws Exception {
        expectNotFound(call(method, path, body, globexShop, globexAgent, globexTeam, testUsers.accessToken(boss)));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("brandEndpoints")
    void ownCustomersWorkspaceWithoutRoleIs404ForWorkspaceManager(HttpMethod method, String path, String body)
            throws Exception {
        expectNotFound(call(method, path, body, brandA, wmOfB, acmeTeam, testUsers.accessToken(wmOfB)));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("brandEndpoints")
    void superAdminAndAccountManagerGet404OnBrandEndpoints(HttpMethod method, String path, String body)
            throws Exception {
        String superAdmin = testUsers.accessToken(testUsers.create("root", "pw", BuiltInRoles.SUPER_ADMIN, null));
        String accountManager = testUsers.accessToken(
                testUsers.create("am", "pw", BuiltInRoles.ACCOUNT_MANAGER, null));
        for (String token : List.of(superAdmin, accountManager)) {
            expectNotFound(call(method, path, body, brandA, wmOfB, acmeTeam, token));
        }
    }

    @Test
    void otherCustomersUserIdIs404() throws Exception {
        String bossToken = testUsers.accessToken(boss);
        expectNotFound(mvc.perform(MockMvcRequestBuilders.post("/api/users/" + globexAgent.getId() + "/disable")
                .header("Authorization", "Bearer " + bossToken)));
        expectNotFound(mvc.perform(MockMvcRequestBuilders.post("/api/users/" + globexAgent.getId() + "/enable")
                .header("Authorization", "Bearer " + bossToken)));
        // A foreign user id under the caller's own workspace is equally unknown.
        for (Arguments a : brandEndpoints().filter(a -> ((String) a.get()[1]).contains("{user}")).toList()) {
            expectNotFound(call((HttpMethod) a.get()[0], (String) a.get()[1], (String) a.get()[2], brandA,
                    globexAgent, acmeTeam, bossToken));
        }
    }
}
