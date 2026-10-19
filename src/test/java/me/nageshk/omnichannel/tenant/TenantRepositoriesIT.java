package me.nageshk.omnichannel.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import me.nageshk.omnichannel.support.TestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;

@SpringBootTest
class TenantRepositoriesIT {

    @Autowired TestUsers testUsers;
    @Autowired WorkspaceRepository workspaces;
    @Autowired TeamRepository teams;

    @BeforeEach
    void clean() {
        testUsers.deleteAll();
    }

    private Team team(String customerId, String workspaceId, String name) {
        Team t = new Team();
        t.setCustomerId(customerId);
        t.setWorkspaceId(workspaceId);
        t.setName(name);
        return t;
    }

    @Test
    void workspaceNameUniquePerCustomer() {
        Customer a = testUsers.customer("Acme");
        Customer b = testUsers.customer("Beta");
        testUsers.workspace(a.getId(), "Support");
        testUsers.workspace(b.getId(), "Support");

        assertThatThrownBy(() -> testUsers.workspace(a.getId(), "Support")).isInstanceOf(DuplicateKeyException.class);
        assertThat(workspaces.findByCustomerIdOrderByName(a.getId())).extracting(Workspace::getName).containsExactly("Support");
    }

    @Test
    void teamNameUniquePerWorkspace() {
        Customer c = testUsers.customer("Acme");
        Workspace w1 = testUsers.workspace(c.getId(), "Support");
        Workspace w2 = testUsers.workspace(c.getId(), "Sales");
        teams.save(team(c.getId(), w1.getId(), "Tier 1"));
        teams.save(team(c.getId(), w2.getId(), "Tier 1"));

        assertThatThrownBy(() -> teams.save(team(c.getId(), w1.getId(), "Tier 1"))).isInstanceOf(DuplicateKeyException.class);
        assertThat(teams.findByWorkspaceIdOrderByName(w1.getId())).hasSize(1);
        assertThat(teams.findByWorkspaceIdOrderByName(w1.getId()).get(0).getMemberIds()).isEqualTo(List.of());
    }

    @Test
    void findByIdAndCustomerIdHidesOtherCustomersWorkspace() {
        Customer a = testUsers.customer("Acme");
        Customer b = testUsers.customer("Beta");
        Workspace w = testUsers.workspace(a.getId(), "Support");

        assertThat(workspaces.findByIdAndCustomerId(w.getId(), a.getId())).isPresent();
        assertThat(workspaces.findByIdAndCustomerId(w.getId(), b.getId())).isEmpty();
    }
}
