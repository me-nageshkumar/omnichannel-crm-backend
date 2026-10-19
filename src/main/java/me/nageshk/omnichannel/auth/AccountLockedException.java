package me.nageshk.omnichannel.auth;

import org.springframework.security.core.AuthenticationException;

/** Too many failed password or OTP attempts; {@link #retryAfterSeconds()} says when to try again. */
public class AccountLockedException extends AuthenticationException {

    private final long retryAfterSeconds;

    public AccountLockedException(String msg, long retryAfterSeconds) {
        super(msg);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
