package me.nageshk.omnichannel.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import me.nageshk.omnichannel.access.BuiltInRoles;
import me.nageshk.omnichannel.access.Role;
import me.nageshk.omnichannel.access.RoleRepository;
import me.nageshk.omnichannel.config.OmnichannelProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class UserSeederTest {

    private static final String HASH = "$2a$10$IYsaLnFsKXKfLd0AwpzjY.V2orPGsTrdwmEuPIeBb828W3nx7NEqi";

    private UserRepository repo;
    private RoleRepository roles;

    @BeforeEach
    void setUp() {
        repo = mock(UserRepository.class);
        roles = mock(RoleRepository.class);
        when(roles.findByName(BuiltInRoles.SUPER_ADMIN)).thenReturn(Optional.of(role("sa-id")));
        when(roles.findByName(BuiltInRoles.WHATSAPP_DEVICE)).thenReturn(Optional.of(role("dev-id")));
    }

    private static Role role(String id) {
        Role r = new Role();
        r.setId(id);
        return r;
    }

    private static OmnichannelProperties props(OmnichannelProperties.SuperAdmin sa, OmnichannelProperties.DeviceUser du) {
        return new OmnichannelProperties(
                new OmnichannelProperties.Jwt("s", Duration.ofHours(1)), sa, du,
                new OmnichannelProperties.TwoFactor("Omnichannel CRM"),
                new OmnichannelProperties.Ws("*"),
                new OmnichannelProperties.Rpc(Duration.ofSeconds(30), Duration.ofSeconds(5)));
    }

    private static OmnichannelProperties.SuperAdmin superAdmin(String hash) {
        return new OmnichannelProperties.SuperAdmin("Nagesh Kumar", "nageshk", hash, "me@example.in", "9928088743");
    }

    private static OmnichannelProperties.DeviceUser device(String username, String hash) {
        return new OmnichannelProperties.DeviceUser("WhatsApp Bridge", username, hash);
    }

    private User savedUser() {
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(repo).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void seedsSuperAdminWithTwoFactorOff() {
        when(repo.existsByRoleId("sa-id")).thenReturn(false);
        new UserSeeder(repo, roles, props(superAdmin(HASH), device("", ""))).run(null);

        User u = savedUser();
        assertThat(u.getRoleId()).isEqualTo("sa-id");
        assertThat(u.getUsername()).isEqualTo("nageshk");
        assertThat(u.getName()).isEqualTo("Nagesh Kumar");
        assertThat(u.getEmail()).isEqualTo("me@example.in");
        assertThat(u.getMobileNumber()).isEqualTo("9928088743");
        assertThat(u.getPassword()).isEqualTo(HASH);
        assertThat(u.isActive()).isTrue();
        assertThat(u.getTokenVersion()).isZero();
        assertThat(u.getTwoFactor()).isEqualTo(TwoFactor.disabled());
    }

    @Test
    void skipsSuperAdminWhenOneExists() {
        when(repo.existsByRoleId("sa-id")).thenReturn(true);
        new UserSeeder(repo, roles, props(superAdmin(""), device("", ""))).run(null);

        verify(repo, never()).save(any());
    }

    @Test
    void failsWhenNoSuperAdminAndNoPasswordHash() {
        when(repo.existsByRoleId("sa-id")).thenReturn(false);
        UserSeeder seeder = new UserSeeder(repo, roles, props(superAdmin(""), device("", "")));

        assertThatThrownBy(() -> seeder.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("omnichannel.super-admin.password");
        verify(repo, never()).save(any());
    }

    @Test
    void seedsDeviceUserWhenUsernameSet() {
        when(repo.existsByRoleId("sa-id")).thenReturn(true);
        when(repo.existsByUsername("wabridge")).thenReturn(false);
        new UserSeeder(repo, roles, props(superAdmin(""), device("wabridge", HASH))).run(null);

        User u = savedUser();
        assertThat(u.getRoleId()).isEqualTo("dev-id");
        assertThat(u.getUsername()).isEqualTo("wabridge");
        assertThat(u.getName()).isEqualTo("WhatsApp Bridge");
        assertThat(u.getPassword()).isEqualTo(HASH);
        assertThat(u.isActive()).isTrue();
        assertThat(u.getTwoFactor()).isEqualTo(TwoFactor.disabled());
    }

    @Test
    void skipsDeviceUserWhenUsernameBlank() {
        when(repo.existsByRoleId("sa-id")).thenReturn(true);
        new UserSeeder(repo, roles, props(superAdmin(""), device("", HASH))).run(null);

        verify(repo, never()).existsByUsername(any());
        verify(repo, never()).save(any());
    }

    @Test
    void skipsDeviceUserWhenExists() {
        when(repo.existsByRoleId("sa-id")).thenReturn(true);
        when(repo.existsByUsername("wabridge")).thenReturn(true);
        new UserSeeder(repo, roles, props(superAdmin(""), device("wabridge", HASH))).run(null);

        verify(repo, never()).save(any());
    }

    @Test
    void failsWhenDeviceUsernameSetButPasswordBlank() {
        when(repo.existsByRoleId("sa-id")).thenReturn(true);
        when(repo.existsByUsername("wabridge")).thenReturn(false);
        UserSeeder seeder = new UserSeeder(repo, roles, props(superAdmin(""), device("wabridge", "")));

        assertThatThrownBy(() -> seeder.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("omnichannel.device-user.password");
        verify(repo, never()).save(any());
    }

    @Test
    void lowerCasesConfiguredSuperAdminUsername() {
        when(repo.existsByRoleId("sa-id")).thenReturn(false);
        var sa = new OmnichannelProperties.SuperAdmin("Nagesh Kumar", "NageshK", HASH, "", "");
        new UserSeeder(repo, roles, props(sa, device("", ""))).run(null);

        assertThat(savedUser().getUsername()).isEqualTo("nageshk");
    }

    @Test
    void lowerCasesConfiguredDeviceUsernameForExistsCheckAndSave() {
        when(repo.existsByRoleId("sa-id")).thenReturn(true);
        when(repo.existsByUsername("wabridge")).thenReturn(false);
        new UserSeeder(repo, roles, props(superAdmin(""), device("WaBridge", HASH))).run(null);

        assertThat(savedUser().getUsername()).isEqualTo("wabridge");
        verify(repo).existsByUsername("wabridge");
    }
}
