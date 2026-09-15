package in.redlava.wabridge.auth;

import static org.assertj.core.api.Assertions.assertThat;

import in.redlava.wabridge.config.WabridgeProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-that-is-long-enough-0123456789";
    private static final Instant NOW = Instant.parse("2026-09-15T10:00:00Z");

    private JwtService serviceAt(Instant now) {
        var props = new WabridgeProperties(
                new WabridgeProperties.Jwt(SECRET, Duration.ofHours(1)),
                List.of(),
                new WabridgeProperties.Ws("*"),
                new WabridgeProperties.Rpc(Duration.ofSeconds(30), Duration.ofSeconds(5)));
        return new JwtService(props, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void issueThenVerifyReturnsUsername() {
        var svc = serviceAt(NOW);
        var issued = svc.issue("demo");

        assertThat(issued.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
        assertThat(svc.verify(issued.token())).contains("demo");
    }

    @Test
    void verifyRejectsExpiredToken() {
        var issued = serviceAt(NOW).issue("demo");
        var later = serviceAt(NOW.plus(Duration.ofHours(2)));

        assertThat(later.verify(issued.token())).isEmpty();
    }

    @Test
    void verifyRejectsTamperedToken() {
        var svc = serviceAt(NOW);
        var token = svc.issue("demo").token();
        var tampered = token.substring(0, token.length() - 2) + "xx";

        assertThat(svc.verify(tampered)).isEmpty();
        assertThat(svc.verify("not-a-jwt")).isEmpty();
    }
}
