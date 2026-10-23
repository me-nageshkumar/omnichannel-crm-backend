package me.nageshk.omnichannel.security.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class AuthorityTest {

    @Test
    void codesAreUnique() {
        Set<String> codes = Arrays.stream(Authority.values()).map(Authority::code).collect(Collectors.toSet());
        assertThat(codes).hasSize(Authority.values().length).hasSize(43);
    }

    @Test
    void crmAuthoritiesHaveSpecLevelsAndDefaults() {
        assertThat(Authority.CRM_ENABLE.code()).isEqualTo("crm:enable");
        assertThat(Authority.CRM_ENABLE.level()).isEqualTo(me.nageshk.omnichannel.db.access.RoleLevel.PLATFORM);
        assertThat(Authority.CRM_ENABLE.defaultRoles()).containsExactlyInAnyOrder(BuiltInRoles.ACCOUNT_MANAGER);
        for (Authority a : new Authority[] {Authority.CRM_VIEW, Authority.CRM_EDIT}) {
            assertThat(a.level()).isEqualTo(me.nageshk.omnichannel.db.access.RoleLevel.BRAND);
            assertThat(a.defaultRoles()).containsExactlyInAnyOrder(
                    BuiltInRoles.CUSTOMER_ADMIN, BuiltInRoles.WORKSPACE_MANAGER, BuiltInRoles.AGENT);
        }
        for (Authority a : new Authority[] {Authority.CRM_VIEW_ALL, Authority.CRM_DELETE,
                Authority.CRM_SETTINGS, Authority.CRM_EXPORT}) {
            assertThat(a.level()).isEqualTo(me.nageshk.omnichannel.db.access.RoleLevel.BRAND);
            assertThat(a.defaultRoles()).containsExactlyInAnyOrder(
                    BuiltInRoles.CUSTOMER_ADMIN, BuiltInRoles.WORKSPACE_MANAGER);
        }
        assertThat(Authority.CRM_VIEW_ALL.group()).isEqualTo("crm");
    }

    @Test
    void everyCodeMatchesThingColonAction() {
        for (Authority a : Authority.values()) {
            assertThat(a.code()).matches("^[a-z-]+:[a-z-]+$");
        }
    }

    @Test
    void groupIsPrefix() {
        assertThat(Authority.COMPANY_USER_MANAGE.group()).isEqualTo("company-user");
        for (Authority a : Authority.values()) {
            assertThat(a.group()).matches("^[a-z-]+$");
        }
    }

    @Test
    void defaultRolesAreBuiltInAndMayHoldTheAuthority() {
        Set<String> builtIn = Set.of(BuiltInRoles.SUPER_ADMIN, BuiltInRoles.ACCOUNT_MANAGER,
                BuiltInRoles.CUSTOMER_ADMIN, BuiltInRoles.WORKSPACE_MANAGER, BuiltInRoles.AGENT);
        for (Authority a : Authority.values()) {
            for (String r : a.defaultRoles()) {
                assertThat(builtIn).as("%s default role %s", a.code(), r).contains(r);
                assertThat(BuiltInRoles.levelOf(r).mayHold(a.level())).as("%s in %s", a.code(), r).isTrue();
            }
        }
    }

    @Test
    void superAdminIsNeverADefault() {
        for (Authority a : Authority.values()) {
            assertThat(a.defaultRoles()).doesNotContain(BuiltInRoles.SUPER_ADMIN);
        }
    }

    @Test
    void customerAdminDefaultsAreAStrictSupersetOfWorkspaceManager() {
        Set<Authority> ca = defaultsOf(BuiltInRoles.CUSTOMER_ADMIN);
        Set<Authority> wm = defaultsOf(BuiltInRoles.WORKSPACE_MANAGER);
        assertThat(ca).containsAll(wm).hasSizeGreaterThan(wm.size());
    }

    @Test
    void fromCodeFindsKnownAndIgnoresUnknown() {
        assertThat(Authority.fromCode("customer:create")).contains(Authority.CUSTOMER_CREATE);
        assertThat(Authority.fromCode("nope:nope")).isEmpty();
    }

    private static Set<Authority> defaultsOf(String role) {
        return Arrays.stream(Authority.values()).filter(a -> a.defaultRoles().contains(role))
                .collect(Collectors.toCollection(HashSet::new));
    }
}
