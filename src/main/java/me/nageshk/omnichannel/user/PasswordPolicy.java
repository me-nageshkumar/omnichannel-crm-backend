package me.nageshk.omnichannel.user;

import me.nageshk.omnichannel.api.WeakPasswordException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Rules for a password someone chooses: at least 10 characters, and not the one it replaces. */
@Component
public class PasswordPolicy {

    public static final int MIN_LENGTH = 10;

    private final PasswordEncoder encoder;

    public PasswordPolicy(PasswordEncoder encoder) {
        this.encoder = encoder;
    }

    /** @throws WeakPasswordException if the password is too short or matches {@code currentHash} */
    public void check(String newPassword, String currentHash) {
        if (newPassword == null || newPassword.length() < MIN_LENGTH) {
            throw new WeakPasswordException("password must be at least " + MIN_LENGTH + " characters");
        }
        if (currentHash != null && encoder.matches(newPassword, currentHash)) {
            throw new WeakPasswordException("new password must differ from the temporary password");
        }
    }
}
