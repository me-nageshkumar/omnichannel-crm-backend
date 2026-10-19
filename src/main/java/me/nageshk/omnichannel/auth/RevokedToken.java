package me.nageshk.omnichannel.auth;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * An access token ended by logout, keyed by its {@code jti}. The TTL index removes the entry once
 * the token would have expired anyway.
 */
@Document("revoked_tokens")
public class RevokedToken {

    @Id
    private String jti;

    private String username;

    @Indexed(expireAfter = "0s")
    private Instant expiresAt;

    public RevokedToken() {}

    public RevokedToken(String jti, String username, Instant expiresAt) {
        this.jti = jti;
        this.username = username;
        this.expiresAt = expiresAt;
    }

    public String getJti() { return jti; }
    public String getUsername() { return username; }
    public Instant getExpiresAt() { return expiresAt; }
}
