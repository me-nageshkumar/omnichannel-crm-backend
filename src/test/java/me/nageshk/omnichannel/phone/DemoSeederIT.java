package me.nageshk.omnichannel.phone;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import me.nageshk.omnichannel.access.BuiltInRoles;
import me.nageshk.omnichannel.support.TestUsers;
import me.nageshk.omnichannel.tenant.Customer;
import me.nageshk.omnichannel.tenant.CustomerRepository;
import me.nageshk.omnichannel.tenant.Workspace;
import me.nageshk.omnichannel.tenant.WorkspaceRepository;
import me.nageshk.omnichannel.user.User;
import me.nageshk.omnichannel.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "omnichannel.seed.demo=true")
class DemoSeederIT {

    @Autowired DemoSeeder seeder;
    @Autowired TestUsers testUsers;
    @Autowired CustomerRepository customers;
    @Autowired WorkspaceRepository workspaces;
    @Autowired UserRepository users;
    @Autowired PhoneNumberRepository numbers;

    @BeforeEach
    void clean() {
        testUsers.deleteAll();
    }

    @AfterEach
    void cleanAfter() {
        testUsers.deleteAll();
    }

    private Customer demo() {
        return customers.findAll().stream().filter(c -> c.getName().equals(DemoSeeder.CUSTOMER)).findFirst().orElseThrow();
    }

    @Test
    void seedsCustomerBrandsPeopleAndNumber() {
        seeder.run(null);

        Customer c = demo();
        List<Workspace> brands = workspaces.findByCustomerIdOrderByName(c.getId());
        assertThat(brands).extracting(Workspace::getName).containsExactly("Demo Electronics", "Demo Fashion");
        Workspace fashion = brands.get(1);

        User admin = users.findByUsername("demo-admin").orElseThrow();
        assertThat(admin.getRoleId()).isEqualTo(testUsers.roleId(BuiltInRoles.CUSTOMER_ADMIN));
        for (String[] p : new String[][] {{"demo-manager", BuiltInRoles.WORKSPACE_MANAGER},
                {"demo-lead", BuiltInRoles.TEAM_LEAD}, {"demo-agent", BuiltInRoles.AGENT}}) {
            User u = users.findByUsername(p[0]).orElseThrow();
            assertThat(u.getCustomerId()).isEqualTo(c.getId());
            assertThat(u.getWorkspaceRoles()).singleElement().satisfies(wr -> {
                assertThat(wr.workspaceId()).isEqualTo(fashion.getId());
                assertThat(wr.roleId()).isEqualTo(testUsers.roleId(p[1]));
            });
            assertThat(u.isMustChangePassword()).isTrue();
        }
        assertThat(admin.isMustChangePassword()).isTrue();

        assertThat(numbers.findAll()).singleElement().satisfies(n -> {
            assertThat(n.getNumber()).isEqualTo("919928088743");
            assertThat(n.getLabel()).isEqualTo("Main line");
            assertThat(n.getWorkspaceId()).isEqualTo(fashion.getId());
            assertThat(n.getCustomerId()).isEqualTo(c.getId());
        });
    }

    @Test
    void isIdempotent() {
        seeder.run(null);
        seeder.run(null);
        assertThat(customers.findAll().stream().filter(c -> c.getName().equals(DemoSeeder.CUSTOMER))).hasSize(1);
        assertThat(numbers.count()).isEqualTo(1);
    }

    @Test
    void existingNumberIsLeftAlone() {
        Customer other = testUsers.customer("Other");
        Workspace shop = testUsers.workspace(other.getId(), "Shop");
        PhoneNumber n = new PhoneNumber();
        n.setNumber("919928088743");
        n.setCustomerId(other.getId());
        n.setWorkspaceId(shop.getId());
        numbers.save(n);

        seeder.run(null);

        assertThat(numbers.findAll()).singleElement()
                .satisfies(x -> assertThat(x.getWorkspaceId()).isEqualTo(shop.getId()));
        assertThat(users.findByUsername("demo-manager")).isPresent();
    }
}
