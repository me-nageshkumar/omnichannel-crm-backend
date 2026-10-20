package me.nageshk.omnichannel.auth;

import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * An access token ended by logout, keyed by its {@code jti}. The TTL index removes the entry once
 * the token would have expired anyway.
 */
@Document("revoked_tokens")
@Getter
@NoArgsConstructor
public class RevokedToken {

    @Id
    private String jti;

    private String username;

    @Indexed(expireAfter = "0s")
    private Instant expiresAt;

    public RevokedToken(String jti, String username, Instant expiresAt) {
        this.jti = jti;
        this.username = username;
        this.expiresAt = expiresAt;
    }
}
