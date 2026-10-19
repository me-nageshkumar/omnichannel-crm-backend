package me.nageshk.omnichannel.auth;

import java.util.Optional;
import me.nageshk.omnichannel.access.AccessService;
import me.nageshk.omnichannel.user.UserRepository;
import org.springframework.stereotype.Service;

/**
 * The one place a bearer access token becomes a user, for HTTP and WebSocket alike. Beyond the
 * signature and expiry, the user must still exist, be active, belong to an active customer (if
 * any), and have the same token version as when the token was issued; bumping {@code tokenVersion}
 * therefore logs the user out everywhere. A token whose id was revoked by logout is refused too.
 * Authorities are resolved afresh on every call, so role edits apply on the next request.
 */
@Service
public class AccessTokenAuthenticator {

    private final JwtService jwt;
    private final UserRepository users;
    private final RevokedTokenRepository revoked;
    private final AccessService access;

    public AccessTokenAuthenticator(
            JwtService jwt, UserRepository users, RevokedTokenRepository revoked, AccessService access) {
        this.jwt = jwt;
        this.users = users;
        this.revoked = revoked;
        this.access = access;
    }

    public Optional<AuthenticatedUser> authenticate(String token) {
        return jwt.verifyAccessToken(token)
                .filter(claims -> claims.jti() == null || !revoked.existsById(claims.jti()))
                .flatMap(claims -> users.findByUsername(claims.username())
                        .filter(u -> u.getTokenVersion() == claims.tokenVersion()))
                .map(access::load)
                .filter(AuthenticatedUser::isEnabled);
    }
}
