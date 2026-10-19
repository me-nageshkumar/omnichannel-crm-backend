package me.nageshk.omnichannel.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;

@SpringBootTest
class RoleSeederIT {

    @Autowired RoleSeeder seeder;
    @Autowired RoleRepository roles;
    @Autowired KnownAuthoritiesRepository known;
    @Autowired MongoTemplate mongo;

    @BeforeEach
    void clean() {
        mongo.dropCollection("roles");
        mongo.dropCollection("system_state");
        seeder.seed();
    }

    private Role role(String name) {
        return roles.findByName(name).orElseThrow();
    }

    @Test
    void seedsSevenBuiltInRoles() {
        Map<String, Role> byName = roles.findAll().stream().collect(Collectors.toMap(Role::getName, Function.identity()));
        assertThat(byName.keySet()).containsExactlyInAnyOrder(BuiltInRoles.SUPER_ADMIN,
                BuiltInRoles.ACCOUNT_MANAGER, BuiltInRoles.WHATSAPP_DEVICE, BuiltInRoles.CUSTOMER_ADMIN,
                BuiltInRoles.WORKSPACE_MANAGER, BuiltInRoles.TEAM_LEAD, BuiltInRoles.AGENT);
        byName.forEach((n, r) -> {
            assertThat(r.isBuiltIn()).isTrue();
            assertThat(r.getLevel()).isEqualTo(BuiltInRoles.levelOf(n));
        });
        assertThat(byName.get(BuiltInRoles.SUPER_ADMIN).getAuthorities()).isEmpty();
    }

    @Test
    void builtInRolesGetTheirDefaultAuthorities() {
        assertThat(role(BuiltInRoles.WORKSPACE_MANAGER).getAuthorities())
                .containsExactlyInAnyOrder("workspace:settings", "user:view", "user:manage", "team:manage");
        assertThat(role(BuiltInRoles.AGENT).getAuthorities()).isEmpty();
    }

    @Test
    void seedIsIdempotent() {
        Map<String, String> before = roles.findAll().stream().collect(Collectors.toMap(Role::getName, Role::getId));
        seeder.seed();
        List<Role> after = roles.findAll();
        assertThat(after).hasSize(7);
        assertThat(after.stream().collect(Collectors.toMap(Role::getName, Role::getId))).isEqualTo(before);
    }

    @Test
    void removedAuthorityIsNotReAdded() {
        Role tl = role(BuiltInRoles.TEAM_LEAD);
        tl.getAuthorities().remove("user:view");
        roles.save(tl);
        seeder.seed();
        assertThat(role(BuiltInRoles.TEAM_LEAD).getAuthorities()).doesNotContain("user:view");
    }

    @Test
    void newAuthorityIsAddedToDefaults() {
        KnownAuthorities k = known.findById(KnownAuthorities.ID).orElseThrow();
        k.getCodes().remove("team:manage");
        known.save(k);
        Role wm = role(BuiltInRoles.WORKSPACE_MANAGER);
        wm.getAuthorities().remove("team:manage");
        roles.save(wm);
        seeder.seed();
        assertThat(role(BuiltInRoles.WORKSPACE_MANAGER).getAuthorities()).contains("team:manage");
        assertThat(known.findById(KnownAuthorities.ID).orElseThrow().getCodes()).contains("team:manage");
    }

    @Test
    void customRolesAreUntouched() {
        Role custom = new Role();
        custom.setName("SENIOR_AGENT");
        custom.setDescription("custom");
        custom.setLevel(RoleLevel.BRAND);
        custom.setAuthorities(new java.util.HashSet<>(Set.of("user:view")));
        custom.setBuiltIn(false);
        String id = roles.save(custom).getId();
        seeder.seed();
        Role after = roles.findById(id).orElseThrow();
        assertThat(after.getAuthorities()).containsExactly("user:view");
        assertThat(after.isBuiltIn()).isFalse();
        assertThat(after.getLevel()).isEqualTo(RoleLevel.BRAND);
    }
}
