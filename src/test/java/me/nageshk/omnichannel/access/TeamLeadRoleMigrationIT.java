package me.nageshk.omnichannel.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import me.nageshk.omnichannel.support.TestUsers;
import me.nageshk.omnichannel.user.User;
import me.nageshk.omnichannel.user.UserRepository;
import me.nageshk.omnichannel.user.WorkspaceRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/** The removed TEAM_LEAD role: its holders become agents, the role document goes, and running again changes nothing. */
@SpringBootTest
class TeamLeadRoleMigrationIT {

    private static final String TEAM_LEAD = "TEAM_LEAD";

    @Autowired TeamLeadRoleMigration migration;
    @Autowired RoleRepository roles;
    @Autowired RoleChangeRepository changes;
    @Autowired UserRepository users;
    @Autowired TestUsers testUsers;
    @Autowired MongoTemplate mongo;

    private String teamLeadId;

    @BeforeEach
    void setUp() {
        testUsers.deleteAll();
        mongo.remove(new Query(), RoleChange.class);
        Role tl = new Role();
        tl.setName(TEAM_LEAD);
        tl.setDescription("Leads a team in a workspace");
        tl.setLevel(RoleLevel.BRAND);
        tl.setBuiltIn(true);
        tl.setAuthorities(Set.of("inbox:view", "inbox:view-team"));
        teamLeadId = roles.save(tl).getId();
    }

    @AfterEach
    void tearDown() {
        testUsers.deleteAll();
        mongo.remove(new Query(), RoleChange.class);
    }

    private User user(String username, String roleId, List<WorkspaceRole> workspaceRoles) {
        User u = new User();
        u.setName(username);
        u.setUsername(username);
        u.setPassword("x");
        u.setActive(true);
        u.setCustomerId("c1");
        u.setRoleId(roleId);
        u.setWorkspaceRoles(workspaceRoles);
        return users.save(u);
    }

    @Test
    void teamLeadUsersBecomeAgentsAndTheRoleIsDeleted() {
        String agentId = roles.findByName(BuiltInRoles.AGENT).orElseThrow().getId();
        User company = user("company-lead", teamLeadId, List.of());
        User brand = user("brand-lead", null, List.of(new WorkspaceRole("w1", teamLeadId),
                new WorkspaceRole("w2", agentId)));

        migration.migrate();

        assertThat(users.findById(company.getId()).orElseThrow().getRoleId()).isEqualTo(agentId);
        assertThat(users.findById(brand.getId()).orElseThrow().getWorkspaceRoles())
                .extracting(WorkspaceRole::roleId).containsExactly(agentId, agentId);
        assertThat(roles.findByName(TEAM_LEAD)).isEmpty();
        List<RoleChange> recorded = changes.findAll();
        assertThat(recorded).hasSize(1);
        assertThat(recorded.get(0).getRoleName()).isEqualTo(TEAM_LEAD);
        assertThat(recorded.get(0).getAction()).isEqualTo(RoleChange.Action.DELETED);
        assertThat(recorded.get(0).getChangedBy()).isEqualTo("system");
    }

    @Test
    void migrationIsIdempotent() {
        user("company-lead", teamLeadId, List.of());
        migration.migrate();
        migration.migrate();
        assertThat(changes.findAll()).hasSize(1);
        assertThat(roles.findByName(TEAM_LEAD)).isEmpty();
    }
}
