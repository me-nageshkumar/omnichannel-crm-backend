package me.nageshk.omnichannel.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.data.mongodb.core.MongoTemplate;

@SpringBootTest
class RoleSeederIT {

    @Autowired RoleSeeder seeder;
    @MockitoSpyBean RoleRepository roles;
    @MockitoSpyBean AuthorityDefinitionRepository authorities;
    @Autowired MongoTemplate mongo;

    @BeforeEach
    void clean() {
        reset(authorities, roles);
        mongo.remove(new org.springframework.data.mongodb.core.query.Query(), "roles");
        mongo.remove(new org.springframework.data.mongodb.core.query.Query(), "authorities");
        mongo.remove(new org.springframework.data.mongodb.core.query.Query(), "system_state");
        seeder.seed();
    }

    private Role role(String name) {
        return roles.findByName(name).orElseThrow();
    }

    @Test
    void seedsSevenBuiltInRoles() {
        Map<String, Role> byName = roles.findAll().stream().collect(Collectors.toMap(Role::getName, Function.identity()));
        assertThat(byName.keySet()).containsExactlyInAnyOrder(BuiltInRoles.SUPER_ADMIN,
                BuiltInRoles.ACCOUNT_MANAGER, BuiltInRoles.CUSTOMER_ADMIN,
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
                .containsExactlyInAnyOrder("workspace:settings", "user:view", "user:manage", "user:enable", "phone-number:view",
                        "phone-number:manage", "team:manage", "contact:view", "contact:annotate", "contact:edit",
                        "contact:import", "contact:export", "contact:bulk", "contact:delete", "contact-field:manage");
        assertThat(role(BuiltInRoles.AGENT).getAuthorities())
                .containsExactlyInAnyOrder("contact:view", "contact:annotate");
    }

    @Test
    void seedIsIdempotent() {
        Map<String, String> before = roles.findAll().stream().collect(Collectors.toMap(Role::getName, Role::getId));
        seeder.seed();
        List<Role> after = roles.findAll();
        assertThat(after).hasSize(6);
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

    private static final String KNOWN = "known-authorities";

    private void clearAll() {
        for (String c : List.of("roles", "authorities", "system_state"))
            mongo.remove(new org.springframework.data.mongodb.core.query.Query(), c);
    }

    @Test
    void freshDatabaseCreatesDocumentsAndGivesDefaults() {
        clearAll();
        seeder.seed();
        assertThat(authorities.findAll()).hasSize(Authority.values().length).hasSize(24)
                .allMatch(AuthorityDefinition::isBuiltIn)
                .allMatch(d -> "system".equals(d.getCreatedBy()));
        AuthorityDefinition cv = authorities.findById("customer:view").orElseThrow();
        assertThat(cv.getGroup()).isEqualTo("customer");
        assertThat(cv.getLevel()).isEqualTo(RoleLevel.PLATFORM);
        assertThat(cv.getDescription()).isEqualTo(Authority.CUSTOMER_VIEW.description());
        assertThat(role(BuiltInRoles.ACCOUNT_MANAGER).getAuthorities()).contains("customer:view");
    }

    @Test
    void migrationKeepsRemovalsAndDropsKnownAuthorities() {
        Role tl = role(BuiltInRoles.TEAM_LEAD);
        tl.getAuthorities().remove("user:view");
        roles.save(tl);
        mongo.remove(new org.springframework.data.mongodb.core.query.Query(), "authorities");
        mongo.insert(new org.bson.Document("_id", KNOWN).append("codes",
                Arrays.stream(Authority.values()).map(Authority::code).toList()), "system_state");
        seeder.seed();
        assertThat(authorities.findAll()).hasSize(24);
        assertThat(role(BuiltInRoles.TEAM_LEAD).getAuthorities()).doesNotContain("user:view");
        assertThat(mongo.findById(KNOWN, org.bson.Document.class, "system_state")).isNull();
    }

    @Test
    void secondStartChangesNothing() {
        AuthorityDefinition d = authorities.findById("customer:view").orElseThrow();
        d.setDescription("x");
        d.setGroup("y");
        authorities.save(d);
        seeder.seed();
        AuthorityDefinition after = authorities.findById("customer:view").orElseThrow();
        assertThat(after.getDescription()).isEqualTo("x");
        assertThat(after.getGroup()).isEqualTo("y");
    }

    @Test
    void builtInDriftIsCorrected() {
        AuthorityDefinition d = authorities.findById("customer:view").orElseThrow();
        d.setLevel(RoleLevel.BRAND);
        d.setBuiltIn(false);
        authorities.save(d);
        seeder.seed();
        AuthorityDefinition after = authorities.findById("customer:view").orElseThrow();
        assertThat(after.getLevel()).isEqualTo(RoleLevel.PLATFORM);
        assertThat(after.isBuiltIn()).isTrue();
    }

    @Test
    void removedDefaultStaysRemovedAfterMigration() {
        Role am = role(BuiltInRoles.ACCOUNT_MANAGER);
        am.getAuthorities().remove("customer:view");
        roles.save(am);
        seeder.seed();
        assertThat(role(BuiltInRoles.ACCOUNT_MANAGER).getAuthorities()).doesNotContain("customer:view");
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

    @Test
    void crashWhileGrantingLeavesAuthorityUnseenSoRetryGrantsIt() {
        Role am = role(BuiltInRoles.ACCOUNT_MANAGER);
        am.getAuthorities().remove("customer:view");
        roles.save(am);
        authorities.deleteById("customer:view");
        doThrow(new IllegalStateException("simulated crash")).when(roles)
                .save(argThat(r -> r != null && r.getAuthorities().contains("customer:view")));

        assertThatThrownBy(() -> seeder.seed()).isInstanceOf(IllegalStateException.class);
        reset(roles);
        // the grant failed, so the authority must not count as seen
        assertThat(authorities.findById("customer:view")).isEmpty();

        seeder.seed();

        assertThat(authorities.findById("customer:view")).isPresent();
        assertThat(role(BuiltInRoles.ACCOUNT_MANAGER).getAuthorities().stream()
                .filter("customer:view"::equals).count()).isEqualTo(1);
    }
}
