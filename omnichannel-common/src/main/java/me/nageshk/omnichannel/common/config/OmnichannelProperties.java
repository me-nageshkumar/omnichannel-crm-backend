package me.nageshk.omnichannel.common.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "omnichannel")
public record OmnichannelProperties(
        Jwt jwt, SuperAdmin superAdmin, TwoFactor twoFactor, Ws ws, Rpc rpc, History history) {

    public record Jwt(String secret, @DefaultValue("12h") Duration ttl) {}

    /**
     * Seeded once, with two-factor off, when no SUPER_ADMIN exists. {@code password} is a bare
     * bcrypt hash.
     */
    public record SuperAdmin(
            @DefaultValue("Nagesh Kumar") String name,
            @DefaultValue("nageshk") String username,
            String password,
            String email,
            String mobileNumber) {}

    public record TwoFactor(@DefaultValue("Omnichannel CRM") String issuer) {}

    public record Ws(@DefaultValue("chrome-extension://*") String allowedOrigins) {}

    public record Rpc(
            @DefaultValue("30s") Duration timeout,
            @DefaultValue("5s") Duration authTimeout) {}

    /** Chat history import: the runner's tick and how hard it pulls from the phone. */
    public record History(
            @DefaultValue("true") boolean autoTick,
            @DefaultValue("5s") Duration tick,
            @DefaultValue("100") int pageSize,
            @DefaultValue("20") int maxLoads,
            @DefaultValue("400ms") Duration pageDelay,
            @DefaultValue("1s") Duration chatDelay,
            @DefaultValue("5000") int maxMessagesPerChat,
            @DefaultValue("3") int maxEmptyPages,
            @DefaultValue("25") int maxIdlePages,
            @DefaultValue("60s") Duration timeBudget,
            @DefaultValue("3") int maxTimeoutsPerChat,
            @DefaultValue("7d") Duration waitingWindow) {}
}
