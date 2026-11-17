package me.nageshk.omnichannel.ai.connection;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.ai.api.AiConnectionField;
import me.nageshk.omnichannel.ai.api.AiConnectionField.Kind;
import me.nageshk.omnichannel.common.net.HttpAddressGuard;
import org.junit.jupiter.api.Test;

class ConnectionSettingsTest {

    private static final List<AiConnectionField> FIELDS = List.of(
            new AiConnectionField("org", "Org", null, Kind.TEXT, false),
            new AiConnectionField("region", "Region", null, Kind.TEXT, true),
            new AiConnectionField("endpoint", "Endpoint", null, Kind.URL, false));

    private final HttpAddressGuard guard = new HttpAddressGuard(false);
    private final Map<String, String> errors = new LinkedHashMap<>();

    private Map<String, String> clean(Map<String, String> in) {
        return ConnectionSettings.clean(FIELDS, in, guard, errors);
    }

    private static Map<String, String> of(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void keepsDeclaredValuesTrimmed() {
        Map<String, String> out = clean(of("region", " eu ", "org", "o1"));

        assertThat(out).containsExactly(Map.entry("org", "o1"), Map.entry("region", "eu"));
        assertThat(errors).isEmpty();
    }

    @Test
    void blankIsAbsent() {
        assertThat(clean(of("region", "eu", "org", "  "))).containsExactly(Map.entry("region", "eu"));
        assertThat(errors).isEmpty();
    }

    @Test
    void nullInputMissesRequired() {
        clean(null);

        assertThat(errors).containsExactly(Map.entry("settings.region", "is required"));
    }

    @Test
    void undeclaredKeyIsAnError() {
        Map<String, String> out = clean(of("region", "eu", "apiKey", "sk"));

        assertThat(errors).containsExactly(Map.entry("settings.apiKey", "is not a setting of this provider"));
        assertThat(out).doesNotContainKey("apiKey");
    }

    @Test
    void tooLong() {
        clean(of("region", "x".repeat(501)));

        assertThat(errors).containsExactly(Map.entry("settings.region", "must be at most 500 characters"));
    }

    @Test
    void controlCharacterRefused() {
        Map<String, String> out = clean(of("region", "eu\r\nX-Evil: 1"));

        assertThat(errors).containsExactly(Map.entry("settings.region", "must not contain control characters"));
        assertThat(out).doesNotContainKey("region");
    }

    @Test
    void deleteCharacterRefused() {
        clean(of("region", "eu\u007f"));

        assertThat(errors).containsKey("settings.region");
    }

    @Test
    void urlMustBeHttps() {
        clean(of("region", "eu", "endpoint", "http://example.com"));

        assertThat(errors).containsExactly(Map.entry("settings.endpoint", "must be an https URL"));
    }

    @Test
    void urlMustBeWellFormed() {
        clean(of("region", "eu", "endpoint", "https://exa mple.com"));

        assertThat(errors).containsExactly(Map.entry("settings.endpoint", "must be an https URL"));
    }

    @Test
    void urlMustBePublic() {
        clean(of("region", "eu", "endpoint", "https://127.0.0.1/x"));

        assertThat(errors).containsExactly(Map.entry("settings.endpoint", "must be a public address"));
    }

    @Test
    void publicUrlIsKept() {
        // a literal public address: no DNS needed
        assertThat(clean(of("region", "eu", "endpoint", "https://8.8.8.8/v1")))
                .containsEntry("endpoint", "https://8.8.8.8/v1");
        assertThat(errors).isEmpty();
    }

    @Test
    void noFieldsNoInput() {
        assertThat(ConnectionSettings.clean(List.of(), Map.of(), guard, errors)).isEmpty();
        assertThat(errors).isEmpty();
    }

    @Test
    void invisibleCharacterRefused() {
        // a zero-width space rides along with a pasted id; the HTTP client would refuse it as a header value
        Map<String, String> out = clean(of("region", "eu\u200b"));

        assertThat(errors).containsExactly(Map.entry("settings.region", "must be plain text without special or invisible characters"));
        assertThat(out).doesNotContainKey("region");
    }

    @Test
    void nonAsciiRefused() {
        clean(of("region", "caf\u00e9"));

        assertThat(errors).containsExactly(Map.entry("settings.region", "must be plain text without special or invisible characters"));
    }

    @Test
    void refusalNamesTheSettingsOnlyWhenThereAreSome() {
        var e = new me.nageshk.omnichannel.ai.provider.AiProviderException(
                me.nageshk.omnichannel.ai.provider.AiProviderException.Code.AUTH, "No such organization");

        assertThat(AiConnectionService.refused(new me.nageshk.omnichannel.ai.provider.AiCredential("k"), e))
                .isEqualTo("The provider refused the key");
        assertThat(AiConnectionService.refused(
                new me.nageshk.omnichannel.ai.provider.AiCredential("k", Map.of("organization", "o")), e))
                .isEqualTo("The provider refused the key or the connection's settings: No such organization");
    }
}
