package me.nageshk.omnichannel.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "omnichannel")
public record OmnichannelProperties(
        Jwt jwt, SuperAdmin superAdmin, DeviceUser deviceUser, TwoFactor twoFactor, Ws ws, Rpc rpc) {

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

    /** Two-factor-off user for the Chrome extension; seeding skipped when {@code username} is blank. */
    public record DeviceUser(
            @DefaultValue("WhatsApp Bridge") String name, String username, String password) {}

    public record TwoFactor(@DefaultValue("Omnichannel CRM") String issuer) {}

    public record Ws(@DefaultValue("chrome-extension://*") String allowedOrigins) {}

    public record Rpc(
            @DefaultValue("30s") Duration timeout,
            @DefaultValue("5s") Duration authTimeout) {}
}
