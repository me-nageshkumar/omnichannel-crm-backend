package me.nageshk.omnichannel.auth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.exceptions.CodeGenerationException;
import dev.samstevens.totp.time.TimeProvider;
import java.time.Duration;
import me.nageshk.omnichannel.config.OmnichannelProperties;
import org.junit.jupiter.api.Test;

class TotpServiceTest {

    private static final long NOW_SECONDS = 1_700_000_000L;
    private static final String SECRET = "JBSWY3DPEHPK3PXP";

    private static TotpService service() {
        TimeProvider fixed = () -> NOW_SECONDS;
        var props = new OmnichannelProperties(
                new OmnichannelProperties.Jwt("s", Duration.ofHours(1)),
                null,
                new OmnichannelProperties.TwoFactor("Omnichannel CRM"),
                new OmnichannelProperties.Ws("*"),
                new OmnichannelProperties.Rpc(Duration.ofSeconds(30), Duration.ofSeconds(5)));
        return new TotpService(props, fixed);
    }

    @Test
    void verifiesCodeGeneratedForSameWindow() throws CodeGenerationException {
        String code = new DefaultCodeGenerator().generate(SECRET, NOW_SECONDS / 30);
        assertThat(service().verify(SECRET, code)).isTrue();
    }

    @Test
    void rejectsWrongCode() {
        assertThat(service().verify(SECRET, "000000")).isFalse();
        assertThat(service().verify(SECRET, null)).isFalse();
        assertThat(service().verify(SECRET, "")).isFalse();
    }

    @Test
    void generatedSecretIsBase32OfLength32() {
        String secret = service().generateSecret();
        assertThat(secret).hasSize(32).matches("[A-Z2-7]+");
        assertThat(service().generateSecret()).isNotEqualTo(secret);
    }

    @Test
    void qrCodeIsPngDataUri() {
        assertThat(service().qrCodeDataUri(SECRET, "alice")).startsWith("data:image/png;base64,");
    }
}
