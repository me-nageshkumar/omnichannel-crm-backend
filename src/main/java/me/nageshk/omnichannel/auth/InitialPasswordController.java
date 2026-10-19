package me.nageshk.omnichannel.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Locale;
import me.nageshk.omnichannel.access.AccessService;
import me.nageshk.omnichannel.api.InvalidTokenException;
import me.nageshk.omnichannel.user.PasswordPolicy;
import me.nageshk.omnichannel.user.User;
import me.nageshk.omnichannel.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ends the first-login flow: trades a password-change token and a new password for an access token.
 * Permitted without an authenticated principal in {@code SecurityConfig}; the change token in the
 * {@code Authorization} header is the credential, and it works only while the user is active, its
 * customer is active, and {@code mustChangePassword} is still set.
 */
@RestController
@RequestMapping("/api/auth/initial-password")
public class InitialPasswordController {

    public record InitialPasswordRequest(@NotNull String newPassword) {}

    public record TokenReply(String token, String expiresAt) {}

    private static final String BEARER = "Bearer ";

    private final JwtService jwt;
    private final UserRepository users;
    private final AccessService access;
    private final PasswordPolicy policy;
    private final PasswordEncoder encoder;

    public InitialPasswordController(
            JwtService jwt, UserRepository users, AccessService access, PasswordPolicy policy, PasswordEncoder encoder) {
        this.jwt = jwt;
        this.users = users;
        this.access = access;
        this.policy = policy;
        this.encoder = encoder;
    }

    @PostMapping
    public TokenReply change(
            @RequestHeader(value = "Authorization", required = false) String header,
            @Valid @RequestBody InitialPasswordRequest req) {
        User user = userFor(header);
        policy.check(req.newPassword(), user.getPassword());
        user.setPassword(encoder.encode(req.newPassword()));
        user.setMustChangePassword(false);
        user.setTokenVersion(user.getTokenVersion() + 1);
        users.save(user);
        JwtService.IssuedToken issued = jwt.issueAccessToken(access.load(user));
        return new TokenReply(issued.token(), issued.expiresAt().toString());
    }

    private User userFor(String header) {
        InvalidTokenException invalid = new InvalidTokenException("invalid or expired token");
        if (header == null || !header.startsWith(BEARER)) throw invalid;
        String username = jwt.verifyPasswordChangeToken(header.substring(BEARER.length())).orElseThrow(() -> invalid);
        User user = users.findByUsername(username.toLowerCase(Locale.ROOT)).orElseThrow(() -> invalid);
        if (!user.isMustChangePassword() || !access.load(user).isEnabled()) throw invalid;
        return user;
    }
}
