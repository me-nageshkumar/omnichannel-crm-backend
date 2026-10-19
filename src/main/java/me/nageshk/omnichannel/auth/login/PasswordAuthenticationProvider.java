package me.nageshk.omnichannel.auth.login;

import me.nageshk.omnichannel.auth.AccountLockedException;
import me.nageshk.omnichannel.auth.AuthenticatedUser;
import me.nageshk.omnichannel.auth.LoginAttemptService;
import me.nageshk.omnichannel.auth.MongoUserDetailsService;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Checks lockout, loads the user, verifies the password, and counts failures. */
@Component
public class PasswordAuthenticationProvider implements AuthenticationProvider {

    private final MongoUserDetailsService users;
    private final PasswordEncoder encoder;
    private final LoginAttemptService attempts;

    public PasswordAuthenticationProvider(
            MongoUserDetailsService users, PasswordEncoder encoder, LoginAttemptService attempts) {
        this.users = users;
        this.encoder = encoder;
        this.attempts = attempts;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        String username = authentication.getName();
        String password = String.valueOf(authentication.getCredentials());

        if (attempts.isLoginBlocked(username)) {
            throw new AccountLockedException("too many failed logins", attempts.loginBlockRemainingSeconds(username));
        }
        AuthenticatedUser user;
        try {
            user = users.loadUserByUsername(username);
        } catch (UsernameNotFoundException e) {
            throw new BadCredentialsException("invalid_credentials", e);
        }
        if (!user.isEnabled()) {
            throw new DisabledException("account_inactive");
        }
        if (!encoder.matches(password, user.getPassword())) {
            attempts.recordFailedLogin(username);
            throw new BadCredentialsException("invalid_credentials");
        }
        attempts.clearLogin(username);
        return PasswordAuthentication.authenticated(user);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return PasswordAuthentication.class.isAssignableFrom(authentication);
    }
}
