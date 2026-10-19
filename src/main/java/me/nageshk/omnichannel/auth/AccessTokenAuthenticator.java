package me.nageshk.omnichannel.auth;

import java.util.Optional;
import me.nageshk.omnichannel.user.UserRepository;
import org.springframework.stereotype.Service;

/**
 * The one place a bearer access token becomes a user, for HTTP and WebSocket alike. Beyond the
 * signature and expiry, the user must still exist, be active, and have the same token version as
 * when the token was issued; bumping {@code tokenVersion} therefore logs the user out everywhere.
 */
@Service
public class AccessTokenAuthenticator {

    private final JwtService jwt;
    private final UserRepository users;

    public AccessTokenAuthenticator(JwtService jwt, UserRepository users) {
        this.jwt = jwt;
        this.users = users;
    }

    public Optional<AuthenticatedUser> authenticate(String token) {
        return jwt.verifyAccessToken(token)
                .flatMap(claims -> users.findByUsername(claims.username())
                        .filter(u -> u.isActive() && u.getTokenVersion() == claims.tokenVersion()))
                .map(AuthenticatedUser::new);
    }
}
