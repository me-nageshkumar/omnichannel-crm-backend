package me.nageshk.omnichannel.security.user;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import me.nageshk.omnichannel.common.api.WeakPasswordException;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class PasswordPolicyTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final PasswordPolicy policy = new PasswordPolicy(encoder);
    private final String currentHash = encoder.encode("temporary-pw-1");

    @Test
    void nineCharactersRejected() {
        assertThatThrownBy(() -> policy.check("123456789", currentHash)).isInstanceOf(WeakPasswordException.class);
    }

    @Test
    void tenCharactersAccepted() {
        assertThatCode(() -> policy.check("1234567890", currentHash)).doesNotThrowAnyException();
    }

    @Test
    void sameAsCurrentRejected() {
        assertThatThrownBy(() -> policy.check("temporary-pw-1", currentHash))
                .isInstanceOf(WeakPasswordException.class);
    }

    @Test
    void nullRejected() {
        assertThatThrownBy(() -> policy.check(null, currentHash)).isInstanceOf(WeakPasswordException.class);
    }
}
