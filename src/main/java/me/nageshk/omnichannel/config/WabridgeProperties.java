package me.nageshk.omnichannel.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "wabridge")
public record WabridgeProperties(Jwt jwt, List<User> users, Ws ws, Rpc rpc) {

    public record Jwt(String secret, @DefaultValue("12h") Duration ttl) {}

    /** password uses Spring's DelegatingPasswordEncoder ids: {bcrypt}..., {noop}... */
    public record User(String username, String password) {}

    public record Ws(@DefaultValue("chrome-extension://*") String allowedOrigins) {}

    public record Rpc(
            @DefaultValue("30s") Duration timeout,
            @DefaultValue("5s") Duration authTimeout) {}
}
