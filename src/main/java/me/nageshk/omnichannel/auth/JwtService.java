package me.nageshk.omnichannel.auth;

import me.nageshk.omnichannel.config.WabridgeProperties;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    public record IssuedToken(String token, Instant expiresAt) {}

    private final SecretKey key;
    private final Duration ttl;
    private final Clock clock;

    public JwtService(WabridgeProperties props, Clock clock) {
        this.key = Keys.hmacShaKeyFor(props.jwt().secret().getBytes(StandardCharsets.UTF_8));
        this.ttl = props.jwt().ttl();
        this.clock = clock;
    }

    public IssuedToken issue(String username) {
        Instant now = clock.instant();
        Instant exp = now.plus(ttl);
        String token = Jwts.builder()
                .subject(username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(key)
                .compact();
        return new IssuedToken(token, exp);
    }

    public Optional<String> verify(String token) {
        try {
            String subject = Jwts.parser()
                    .verifyWith(key)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload()
                    .getSubject();
            return Optional.ofNullable(subject).filter(s -> !s.isBlank());
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
