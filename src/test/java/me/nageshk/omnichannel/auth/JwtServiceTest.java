package me.nageshk.omnichannel.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import me.nageshk.omnichannel.config.OmnichannelProperties;
import me.nageshk.omnichannel.user.TwoFactor;
import me.nageshk.omnichannel.user.User;
import me.nageshk.omnichannel.user.UserRole;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-that-is-long-enough-0123456789";
    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00Z");

    private static JwtService serviceAt(Instant now) {
        var props = new OmnichannelProperties(
                new OmnichannelProperties.Jwt(SECRET, Duration.ofHours(1)),
                null, null, null,
                new OmnichannelProperties.Ws("*"),
                new OmnichannelProperties.Rpc(Duration.ofSeconds(30), Duration.ofSeconds(5)));
        return new JwtService(props, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static AuthenticatedUser user() {
        User u = new User();
        u.setId("64f0c0ffee");
        u.setUsername("alice");
        u.setPassword("hash");
        u.setRole(UserRole.ADMIN);
        u.setActive(true);
        u.setTwoFactor(TwoFactor.enabledWith("JBSWY3DPEHPK3PXP"));
        u.setTokenVersion(3L);
        return new AuthenticatedUser(u);
    }

    @Test
    void accessTokenCarriesClaimsAndExpiry() {
        var svc = serviceAt(NOW);
        var issued = svc.issueAccessToken(user());

        assertThat(issued.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
        var claims = svc.verifyAccessToken(issued.token());
        assertThat(claims).isPresent();
        assertThat(claims.get().username()).isEqualTo("alice");
        assertThat(claims.get().tokenVersion()).isEqualTo(3L);
        var raw = svc.parse(issued.token());
        assertThat(raw.get("userId", String.class)).isEqualTo("64f0c0ffee");
        assertThat(raw.get("role", String.class)).isEqualTo("ADMIN");
        assertThat(raw.get("twoFactorEnabled", Boolean.class)).isTrue();
    }

    @Test
    void accessTokenRejectedWhenExpired() {
        var token = serviceAt(NOW).issueAccessToken(user()).token();
        assertThat(serviceAt(NOW.plus(Duration.ofHours(2))).verifyAccessToken(token)).isEmpty();
    }

    @Test
    void accessTokenRejectedWhenTampered() {
        var svc = serviceAt(NOW);
        var token = svc.issueAccessToken(user()).token();
        var tampered = token.substring(0, token.length() - 2) + "xx";

        assertThat(svc.verifyAccessToken(tampered)).isEmpty();
        assertThat(svc.verifyAccessToken("not-a-jwt")).isEmpty();
    }

    @Test
    void pendingTokenExpiresInFiveMinutes() {
        var pending = serviceAt(NOW).issuePendingToken("alice");

        assertThat(serviceAt(NOW.plus(Duration.ofMinutes(4))).verifyPendingToken(pending)).contains("alice");
        assertThat(serviceAt(NOW.plus(Duration.ofMinutes(6))).verifyPendingToken(pending)).isEmpty();
    }

    @Test
    void pendingTokenIsNotAnAccessToken() {
        var svc = serviceAt(NOW);
        assertThat(svc.verifyAccessToken(svc.issuePendingToken("alice"))).isEmpty();
    }

    @Test
    void accessTokenIsNotAPendingToken() {
        var svc = serviceAt(NOW);
        assertThat(svc.verifyPendingToken(svc.issueAccessToken(user()).token())).isEmpty();
    }

    @Test
    void accessTokenCarriesUniqueJti() {
        var svc = serviceAt(NOW);
        var a = svc.verifyAccessToken(svc.issueAccessToken(user()).token()).orElseThrow();
        var b = svc.verifyAccessToken(svc.issueAccessToken(user()).token()).orElseThrow();

        assertThat(a.jti()).isNotBlank();
        assertThat(b.jti()).isNotBlank().isNotEqualTo(a.jti());
        assertThat(a.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
    }

    @Test
    void pendingTokenHasNoJti() {
        var svc = serviceAt(NOW);
        assertThat(svc.parse(svc.issuePendingToken("alice")).getId()).isNull();
    }
}
