package me.nageshk.omnichannel.user;

import java.util.Locale;
import me.nageshk.omnichannel.access.BuiltInRoles;
import me.nageshk.omnichannel.access.RoleRepository;
import me.nageshk.omnichannel.config.OmnichannelProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Seeds the first super admin and the extension's device user on startup. Existing users are never
 * overwritten, so edits made directly in Mongo stick. Not active under the {@code test} profile:
 * integration tests seed their own users.
 */
@Component
@Order(3)
@Profile("!test")
public class UserSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UserSeeder.class);

    private final UserRepository users;
    private final RoleRepository roles;
    private final OmnichannelProperties props;

    public UserSeeder(UserRepository users, RoleRepository roles, OmnichannelProperties props) {
        this.users = users;
        this.roles = roles;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedSuperAdmin(props.superAdmin());
        seedDeviceUser(props.deviceUser());
    }

    private void seedSuperAdmin(OmnichannelProperties.SuperAdmin sa) {
        if (users.existsByRoleId(roleId(BuiltInRoles.SUPER_ADMIN))) return;
        if (isBlank(sa.password())) {
            throw new IllegalStateException(
                    "omnichannel.super-admin.password (bcrypt hash) is required to seed the first super admin");
        }
        User u = base(sa.name(), lower(sa.username()), sa.password(), roleId(BuiltInRoles.SUPER_ADMIN));
        u.setEmail(blankToNull(sa.email()));
        u.setMobileNumber(blankToNull(sa.mobileNumber()));
        u.setTwoFactor(TwoFactor.disabled());
        users.save(u);
        log.info("seeded super admin '{}' (two-factor off)", u.getUsername());
    }

    private void seedDeviceUser(OmnichannelProperties.DeviceUser du) {
        if (isBlank(du.username())) return;
        String username = lower(du.username());
        if (users.existsByUsername(username)) return;
        if (isBlank(du.password())) {
            throw new IllegalStateException(
                    "omnichannel.device-user.password (bcrypt hash) is required when omnichannel.device-user.username is set");
        }
        User u = base(du.name(), username, du.password(), roleId(BuiltInRoles.WHATSAPP_DEVICE));
        u.setTwoFactor(TwoFactor.disabled());
        users.save(u);
        log.info("seeded device user '{}'", u.getUsername());
    }

    private String roleId(String roleName) {
        return roles.findByName(roleName)
                .orElseThrow(() -> new IllegalStateException("built-in role " + roleName + " is missing"))
                .getId();
    }

    private static User base(String name, String username, String passwordHash, String roleId) {
        User u = new User();
        u.setName(name);
        u.setUsername(username);
        u.setPassword(passwordHash);
        u.setRoleId(roleId);
        u.setActive(true);
        u.setTokenVersion(0L);
        return u;
    }

    private static String lower(String username) {
        return username == null ? null : username.toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return isBlank(s) ? null : s;
    }
}
