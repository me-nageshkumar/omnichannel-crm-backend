package me.nageshk.omnichannel.app.enablement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import me.nageshk.omnichannel.app.support.TestUsers;
import me.nageshk.omnichannel.common.api.DisabledAtHigherLevelException;
import me.nageshk.omnichannel.common.enablement.EnableLevel;
import me.nageshk.omnichannel.db.enablement.Disabled;
import me.nageshk.omnichannel.db.enablement.EnablementChangeRepository;
import me.nageshk.omnichannel.db.tenant.Customer;
import me.nageshk.omnichannel.db.tenant.Workspace;
import me.nageshk.omnichannel.db.user.WorkspaceRole;
import me.nageshk.omnichannel.security.access.AccessService;
import me.nageshk.omnichannel.security.access.BuiltInRoles;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.security.enablement.Enablement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class EnablementTest {

    @Autowired Enablement enablement;
    @Autowired EnablementChangeRepository changes;
    @Autowired AccessService access;
    @Autowired TestUsers testUsers;

    Customer acme;
    Workspace w1;

    @BeforeEach
    void seed() {
        testUsers.deleteAll();
        changes.deleteAll();
        acme = testUsers.customer("Acme");
        w1 = testUsers.workspace(acme.getId(), "Brand A");
    }

    @AfterEach
    void clean() {
        testUsers.deleteAll();
        changes.deleteAll();
    }

    private AuthenticatedUser accountManager() {
        return access.load(testUsers.create("am", "pw-12345678", BuiltInRoles.ACCOUNT_MANAGER, null));
    }

    private AuthenticatedUser customerAdmin() {
        return access.load(testUsers.createInCustomer(acme.getId(), "admin", BuiltInRoles.CUSTOMER_ADMIN, List.of()));
    }

    private AuthenticatedUser manager() {
        return access.load(testUsers.createInCustomer(acme.getId(), "wm", null,
                List.of(new WorkspaceRole(w1.getId(), testUsers.roleId(BuiltInRoles.WORKSPACE_MANAGER)))));
    }

    @Test
    void platformStaffActAtPlatform() {
        assertThat(enablement.levelOf(accountManager(), "customer:manage", null)).isEqualTo(EnableLevel.PLATFORM);
    }

    @Test
    void levelComesFromRoleNotAuthority() {
        assertThat(enablement.levelOf(customerAdmin(), "user:manage", w1.getId())).isEqualTo(EnableLevel.COMPANY);
        assertThat(enablement.levelOf(manager(), "user:manage", w1.getId())).isEqualTo(EnableLevel.BRAND);
    }

    @Test
    void lowerDisableDoesNotOverwriteHigher() {
        Disabled company = new Disabled("admin", EnableLevel.COMPANY, Instant.parse("2026-10-20T10:00:00Z"));
        assertThat(enablement.disable(company, manager(), EnableLevel.BRAND)).isSameAs(company);
    }

    @Test
    void higherDisableReplacesLower() {
        Disabled brand = new Disabled("wm", EnableLevel.BRAND, Instant.parse("2026-10-20T10:00:00Z"));
        Disabled after = enablement.disable(brand, customerAdmin(), EnableLevel.COMPANY);
        assertThat(after.by()).isEqualTo("admin");
        assertThat(after.level()).isEqualTo(EnableLevel.COMPANY);
        assertThat(after.at()).isNotNull();
    }

    @Test
    void enableBelowStoredLevelIsRefused() {
        Disabled company = new Disabled("admin", EnableLevel.COMPANY, Instant.now());
        assertThatThrownBy(() -> enablement.checkEnable(company, EnableLevel.BRAND))
                .isInstanceOfSatisfying(DisabledAtHigherLevelException.class,
                        e -> assertThat(e.level()).isEqualTo(EnableLevel.COMPANY));
    }

    @Test
    void enableAtOrAboveLevelIsAllowed() {
        Disabled company = new Disabled("admin", EnableLevel.COMPANY, Instant.now());
        enablement.checkEnable(company, EnableLevel.COMPANY);
        enablement.checkEnable(company, EnableLevel.PLATFORM);
        enablement.checkEnable(null, EnableLevel.BRAND);
    }

    @Test
    void recordWritesAChange() {
        enablement.record("PHONE_NUMBER", "p1", true, false, accountManager(), EnableLevel.PLATFORM);
        assertThat(changes.findAll()).singleElement().satisfies(c -> {
            assertThat(c.getTargetType()).isEqualTo("PHONE_NUMBER");
            assertThat(c.getTargetId()).isEqualTo("p1");
            assertThat(c.isEnabledBefore()).isTrue();
            assertThat(c.isEnabledAfter()).isFalse();
            assertThat(c.getBy()).isEqualTo("am");
            assertThat(c.getLevel()).isEqualTo(EnableLevel.PLATFORM);
            assertThat(c.getAt()).isNotNull();
        });
    }
}
