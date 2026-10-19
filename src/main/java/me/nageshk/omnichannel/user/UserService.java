package me.nageshk.omnichannel.user;

import me.nageshk.omnichannel.auth.AccountLockedException;
import me.nageshk.omnichannel.auth.LoginAttemptService;
import me.nageshk.omnichannel.auth.TotpService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** Self-service account operations for the logged-in user. */
@Service
public class UserService {

    public record TwoFactorSetup(String secret, String qrCodeDataUri, String issuer, String username) {}

    /** The caller's own profile; never carries the password, token version or TOTP secret. */
    public record CurrentUser(
            String id,
            String name,
            String username,
            String email,
            String mobileNumber,
            UserRole role,
            boolean twoFactorEnabled) {}

    private final UserRepository users;
    private final TotpService totp;
    private final LoginAttemptService attempts;

    public UserService(UserRepository users, TotpService totp, LoginAttemptService attempts) {
        this.users = users;
        this.totp = totp;
        this.attempts = attempts;
    }

    public CurrentUser currentUser(String username) {
        User u = require(username);
        return new CurrentUser(
                u.getId(), u.getName(), u.getUsername(), u.getEmail(), u.getMobileNumber(), u.getRole(),
                u.getTwoFactor().enabled());
    }

    /** A fresh secret and QR code; nothing is stored until {@link #confirmTwoFactor}. */
    public TwoFactorSetup initiateTwoFactor(String username) {
        User user = require(username);
        if (user.getTwoFactor().enabled()) {
            throw new TwoFactorStateException("two-factor authentication is already enabled");
        }
        String secret = totp.generateSecret();
        return new TwoFactorSetup(secret, totp.qrCodeDataUri(secret, username), totp.issuer(), username);
    }

    public void confirmTwoFactor(String username, String secret, String otp) {
        User user = require(username);
        if (user.getTwoFactor().enabled()) {
            throw new TwoFactorStateException("two-factor authentication is already enabled");
        }
        verifyWithLockout(username, secret, otp);
        user.setTwoFactor(TwoFactor.enabledWith(secret));
        users.save(user);
    }

    public void disableTwoFactor(String username, String otp) {
        User user = require(username);
        if (!user.getTwoFactor().enabled()) {
            throw new TwoFactorStateException("two-factor authentication is already disabled");
        }
        verifyWithLockout(username, user.getTwoFactor().totpSecret(), otp);
        user.setTwoFactor(TwoFactor.disabled());
        users.save(user);
    }

    private void verifyWithLockout(String username, String secret, String otp) {
        if (attempts.isOtpBlocked(username)) {
            throw new AccountLockedException("too many failed OTP attempts", attempts.otpBlockRemainingSeconds(username));
        }
        if (!totp.verify(secret, otp)) {
            attempts.recordFailedOtp(username);
            throw new InvalidOtpException();
        }
        attempts.clearOtp(username);
    }

    private User require(String username) {
        return users.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("no user '" + username + "'"));
    }
}
