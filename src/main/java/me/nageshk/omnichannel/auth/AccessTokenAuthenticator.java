package me.nageshk.omnichannel.auth;

import java.util.Optional;
import me.nageshk.omnichannel.user.UserRepository;
import org.springframework.stereotype.Service;

/**
 * The one place a bearer access token becomes a user, for HTTP and WebSocket alike. Beyond the
 * signature and expiry, the user must still exist, be active, and have the same token version as
 * when the token was issued; bumping {@code tokenVersion} therefore logs the user out everywhere.
 * A token whose id was revoked by logout is refused too.
 */
@Service
public class AccessTokenAuthenticator {

    private final JwtService jwt;
    private final UserRepository users;
    private final RevokedTokenRepository revoked;

    public AccessTokenAuthenticator(JwtService jwt, UserRepository users, RevokedTokenRepository revoked) {
        this.jwt = jwt;
        this.users = users;
        this.revoked = revoked;
    }

    public Optional<AuthenticatedUser> authenticate(String token) {
        return jwt.verifyAccessToken(token)
                .filter(claims -> claims.jti() == null || !revoked.existsById(claims.jti()))
                .flatMap(claims -> users.findByUsername(claims.username())
                        .filter(u -> u.isActive() && u.getTokenVersion() == claims.tokenVersion()))
                .map(AuthenticatedUser::new);
    }
}
