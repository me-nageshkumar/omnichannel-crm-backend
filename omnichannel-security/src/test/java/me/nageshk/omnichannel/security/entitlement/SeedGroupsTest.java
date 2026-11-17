package me.nageshk.omnichannel.security.entitlement;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import me.nageshk.omnichannel.db.access.RoleLevel;
import me.nageshk.omnichannel.db.billing.GroupCategory;
import me.nageshk.omnichannel.security.access.Authority;
import org.junit.jupiter.api.Test;

class SeedGroupsTest {

    @Test
    void everyCustomerAuthorityIsInExactlyOneSeedGroup() {
        for (Authority a : Authority.values()) {
            long holders = SeedGroups.all().stream().filter(s -> s.holds().test(a)).count();
            if (a.level() == RoleLevel.PLATFORM) {
                assertThat(holders).as(a.code()).isZero();
            } else {
                assertThat(holders).as(a.code()).isEqualTo(1);
            }
        }
    }

    @Test
    void theFourEverydayGroupsAreDefaultAndTheRestArePaid() {
        Map<String, GroupCategory> categories = SeedGroups.all().stream()
                .collect(Collectors.toMap(SeedGroups.Seed::code, SeedGroups.Seed::category));
        assertThat(categories).containsOnly(
                Map.entry("core", GroupCategory.DEFAULT), Map.entry("inbox", GroupCategory.DEFAULT),
                Map.entry("contacts", GroupCategory.DEFAULT), Map.entry("templates", GroupCategory.DEFAULT),
                Map.entry("campaigns", GroupCategory.PAID), Map.entry("automations", GroupCategory.PAID),
                Map.entry("chatbots", GroupCategory.PAID), Map.entry("ai", GroupCategory.PAID),
                Map.entry("crm", GroupCategory.PAID));
    }

    @Test
    void onlyAiRequiresAnotherGroup() {
        for (SeedGroups.Seed s : SeedGroups.all()) {
            if (s.code().equals("ai")) assertThat(s.requires()).containsExactly("chatbots");
            else assertThat(s.requires()).as(s.code()).isEmpty();
        }
    }

    @Test
    void coreLetsACustomerAdminManageUsersAndSeeBilling() {
        Set<String> core = SeedGroups.coreBuiltIns();
        assertThat(core).contains("billing:view", "user:manage", "workspace:create", "company-user:manage",
                "team:manage", "phone-number:manage");
        assertThat(core).doesNotContain("crm:view", "inbox:view");
    }
}
