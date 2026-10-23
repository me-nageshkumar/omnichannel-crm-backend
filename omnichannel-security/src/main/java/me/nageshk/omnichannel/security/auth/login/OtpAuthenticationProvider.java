package me.nageshk.omnichannel.security.auth.login;

import me.nageshk.omnichannel.security.auth.AccountLockedException;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.security.auth.JwtService;
import me.nageshk.omnichannel.security.auth.LoginAttemptService;
import me.nageshk.omnichannel.security.auth.MongoUserDetailsService;
import me.nageshk.omnichannel.security.auth.TotpService;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

/** Resolves the pending token to a user, applies the OTP lockout, and verifies the TOTP code. */
@Component
public class OtpAuthenticationProvider implements AuthenticationProvider {

    private final JwtService jwt;
    private final MongoUserDetailsService users;
    private final TotpService totp;
    private final LoginAttemptService attempts;

    public OtpAuthenticationProvider(
            JwtService jwt, MongoUserDetailsService users, TotpService totp, LoginAttemptService attempts) {
        this.jwt = jwt;
        this.users = users;
        this.totp = totp;
        this.attempts = attempts;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        OtpAuthentication otpAuth = (OtpAuthentication) authentication;
        String username = jwt.verifyPendingToken(otpAuth.principalName())
                .orElseThrow(() -> new CredentialsExpiredException(LoginFailureHandler.INVALID_TOKEN));

        if (attempts.isOtpBlocked(username)) {
            throw new AccountLockedException("too many failed OTP attempts", attempts.otpBlockRemainingSeconds(username));
        }
        AuthenticatedUser user;
        try {
            user = users.loadUserByUsername(username);
        } catch (UsernameNotFoundException e) {
            throw new CredentialsExpiredException(LoginFailureHandler.INVALID_TOKEN, e);
        }
        if (!user.isEnabled()) {
            throw new DisabledException(LoginFailureHandler.ACCOUNT_INACTIVE);
        }
        if (!totp.verify(user.totpSecret(), otpAuth.otp())) {
            attempts.recordFailedOtp(username);
            throw new BadCredentialsException(LoginFailureHandler.INVALID_OTP);
        }
        attempts.clearOtp(username);
        return OtpAuthentication.authenticated(user);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return OtpAuthentication.class.isAssignableFrom(authentication);
    }
}
