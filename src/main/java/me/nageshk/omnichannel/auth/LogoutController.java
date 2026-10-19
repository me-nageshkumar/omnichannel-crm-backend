package me.nageshk.omnichannel.auth;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ends the calling session: the bearer token's {@code jti} is revoked. Other tokens of the same
 * user keep working. Reaching here means {@link JwtAuthFilter} already accepted the token.
 */
@RestController
public class LogoutController {

    private static final String BEARER = "Bearer ";

    private final JwtService jwt;
    private final RevokedTokenRepository revoked;

    public LogoutController(JwtService jwt, RevokedTokenRepository revoked) {
        this.jwt = jwt;
        this.revoked = revoked;
    }

    /** A token without a {@code jti} (issued before revocation existed) cannot be revoked; it simply expires. */
    @PostMapping("/api/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        jwt.verifyAccessToken(authorization.substring(BEARER.length()))
                .filter(claims -> claims.jti() != null)
                .ifPresent(claims -> revoked.save(new RevokedToken(claims.jti(), claims.username(), claims.expiresAt())));
    }
}
