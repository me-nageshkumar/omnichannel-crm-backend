package me.nageshk.omnichannel.user;

import me.nageshk.omnichannel.config.OmnichannelProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Seeds the first super admin and the extension's device user on startup. Existing users are never
 * overwritten, so edits made directly in Mongo stick. Not active under the {@code test} profile:
 * integration tests seed their own users.
 */
@Component
@Profile("!test")
public class UserSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UserSeeder.class);

    private final UserRepository users;
    private final OmnichannelProperties props;

    public UserSeeder(UserRepository users, OmnichannelProperties props) {
        this.users = users;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedSuperAdmin(props.superAdmin());
        seedDeviceUser(props.deviceUser());
    }

    private void seedSuperAdmin(OmnichannelProperties.SuperAdmin sa) {
        if (users.existsByRole(UserRole.SUPER_ADMIN)) return;
        if (isBlank(sa.password())) {
            throw new IllegalStateException(
                    "omnichannel.super-admin.password (bcrypt hash) is required to seed the first super admin");
        }
        User u = base(sa.name(), sa.username(), sa.password(), UserRole.SUPER_ADMIN);
        u.setEmail(blankToNull(sa.email()));
        u.setMobileNumber(blankToNull(sa.mobileNumber()));
        u.setTwoFactor(TwoFactor.disabled());
        users.save(u);
        log.info("seeded super admin '{}' (two-factor off)", u.getUsername());
    }

    private void seedDeviceUser(OmnichannelProperties.DeviceUser du) {
        if (isBlank(du.username())) return;
        if (users.existsByUsername(du.username())) return;
        if (isBlank(du.password())) {
            throw new IllegalStateException(
                    "omnichannel.device-user.password (bcrypt hash) is required when omnichannel.device-user.username is set");
        }
        User u = base(du.name(), du.username(), du.password(), UserRole.USER);
        u.setTwoFactor(TwoFactor.disabled());
        users.save(u);
        log.info("seeded device user '{}'", u.getUsername());
    }

    private static User base(String name, String username, String passwordHash, UserRole role) {
        User u = new User();
        u.setName(name);
        u.setUsername(username);
        u.setPassword(passwordHash);
        u.setRole(role);
        u.setActive(true);
        u.setTokenVersion(0L);
        return u;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return isBlank(s) ? null : s;
    }
}
