package me.nageshk.omnichannel.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import me.nageshk.omnichannel.config.OmnichannelProperties;
import org.springframework.stereotype.Service;

/**
 * Issues and verifies two kinds of HS256 tokens: access tokens (subject, a unique id + user claims) and
 * five-minute pending tokens handed out after a correct password when two-factor is required.
 */
@Service
public class JwtService {

    public static final String CLAIM_AUTH_STATE = "authState";
    public static final String TWO_FA_PENDING = "TWO_FA_PENDING";
    public static final Duration PENDING_TTL = Duration.ofMinutes(5);

    public static final String CLAIM_USER_ID = "userId";
    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_TOKEN_VERSION = "tokenVersion";
    public static final String CLAIM_TWO_FACTOR_ENABLED = "twoFactorEnabled";

    public record IssuedToken(String token, Instant expiresAt) {}

    /** {@code jti} is null for access tokens issued before tokens carried one. */
    public record AccessClaims(String username, long tokenVersion, String jti, Instant expiresAt) {}

    private final SecretKey key;
    private final Duration ttl;
    private final Clock clock;

    public JwtService(OmnichannelProperties props, Clock clock) {
        this.key = Keys.hmacShaKeyFor(props.jwt().secret().getBytes(StandardCharsets.UTF_8));
        this.ttl = props.jwt().ttl();
        this.clock = clock;
    }

    public IssuedToken issueAccessToken(AuthenticatedUser user) {
        Instant now = clock.instant();
        Instant exp = now.plus(ttl);
        String token = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(user.getUsername())
                .claim(CLAIM_USER_ID, user.userId())
                .claim(CLAIM_ROLE, user.role().name())
                .claim(CLAIM_TOKEN_VERSION, user.tokenVersion())
                .claim(CLAIM_TWO_FACTOR_ENABLED, user.twoFactorEnabled())
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(key)
                .compact();
        return new IssuedToken(token, exp);
    }

    public String issuePendingToken(String username) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(username)
                .claim(CLAIM_AUTH_STATE, TWO_FA_PENDING)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(PENDING_TTL)))
                .signWith(key)
                .compact();
    }

    /** Signature-checked claims; throws {@link JwtException} on any problem. */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .clock(() -> Date.from(clock.instant()))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** Empty for a bad signature, an expired token, or a pending token. */
    public Optional<AccessClaims> verifyAccessToken(String token) {
        return safeParse(token)
                .filter(c -> c.get(CLAIM_AUTH_STATE) == null)
                .filter(c -> c.getSubject() != null && !c.getSubject().isBlank())
                .map(c -> new AccessClaims(
                        c.getSubject(), c.get(CLAIM_TOKEN_VERSION, Long.class), c.getId(), c.getExpiration().toInstant()));
    }

    /** The username only when the token is a live pending token. */
    public Optional<String> verifyPendingToken(String token) {
        return safeParse(token)
                .filter(c -> TWO_FA_PENDING.equals(c.get(CLAIM_AUTH_STATE, String.class)))
                .map(Claims::getSubject);
    }

    private Optional<Claims> safeParse(String token) {
        try {
            return Optional.of(parse(token));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
