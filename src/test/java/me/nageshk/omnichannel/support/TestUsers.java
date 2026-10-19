package me.nageshk.omnichannel.support;

import me.nageshk.omnichannel.user.TwoFactor;
import me.nageshk.omnichannel.user.User;
import me.nageshk.omnichannel.user.UserRepository;
import me.nageshk.omnichannel.user.UserRole;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Test-only helper that inserts users straight into the (embedded) database. */
@Component
@Profile("test")
public class TestUsers {

    /** A fixed Base32 TOTP secret so tests can generate matching codes. */
    public static final String TEST_SECRET = "JBSWY3DPEHPK3PXP";

    private final UserRepository users;
    private final PasswordEncoder encoder;

    public TestUsers(UserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    /** Creates an active user; {@code totpSecret} null means two-factor off. */
    public User create(String username, String rawPassword, UserRole role, String totpSecret) {
        User u = new User();
        u.setName("Test " + username);
        u.setUsername(username);
        u.setPassword(encoder.encode(rawPassword));
        u.setRole(role);
        u.setActive(true);
        u.setTwoFactor(totpSecret == null ? TwoFactor.disabled() : TwoFactor.enabledWith(totpSecret));
        return users.save(u);
    }

    public void deleteAll() {
        users.deleteAll();
    }
}
