package me.nageshk.omnichannel.common.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.List;
import me.nageshk.omnichannel.common.api.CodedException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class SecretBoxTest {

    private static final String K1 = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String K2 = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    private static SecretBox box(String... keys) {
        return new SecretBox(new SecretsProperties(List.of(keys)));
    }

    @Test
    void sealThenOpenRoundTrips() {
        SecretBox box = box("a:" + K1);
        SealedSecret s = box.seal("sk-test-123");
        assertThat(box.open(s)).isEqualTo("sk-test-123");
        assertThat(s.ciphertext()).doesNotContain("sk-test-123");
        assertThat(s.keyId()).isEqualTo("a");
    }

    @Test
    void twoSealsDiffer() {
        SecretBox box = box("a:" + K1);
        SealedSecret x = box.seal("same");
        SealedSecret y = box.seal("same");
        assertThat(x.iv()).isNotEqualTo(y.iv());
        assertThat(x.ciphertext()).isNotEqualTo(y.ciphertext());
    }

    @Test
    void oldKeyStillOpensAfterRotation() {
        SealedSecret old = box("a:" + K1).seal("secret");
        SecretBox rotated = box("b:" + K2, "a:" + K1);
        assertThat(rotated.open(old)).isEqualTo("secret");
        assertThat(rotated.seal("x").keyId()).isEqualTo("b");
    }

    @Test
    void tamperedCiphertextFails() {
        SecretBox box = box("a:" + K1);
        SealedSecret s = box.seal("secret");
        byte[] bytes = Base64.getDecoder().decode(s.ciphertext());
        bytes[0] ^= 1;
        SealedSecret bad = new SealedSecret(s.keyId(), s.iv(), Base64.getEncoder().encodeToString(bytes));
        assertThatThrownBy(() -> box.open(bad)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unknownKeyIdFails() {
        SealedSecret s = box("a:" + K1).seal("secret");
        assertThatThrownBy(() -> box("b:" + K2).open(s)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void noKeysMeansNotReadyAndSealThrows() {
        for (SecretBox box : List.of(new SecretBox(new SecretsProperties(null)), new SecretBox(new SecretsProperties(List.of())),
                box(""))) {
            assertThat(box.ready()).isFalse();
            assertThatThrownBy(() -> box.seal("x")).isInstanceOfSatisfying(CodedException.class, e -> {
                assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(e.code()).isEqualTo("secrets_unavailable");
                assertThat(e.getMessage()).isEqualTo("Secrets cannot be saved: the server has no encryption key configured");
            });
        }
    }

    @Test
    void badKeyConfigFailsAtStartup() {
        String short16 = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> box("a:" + short16)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> box("nocolon")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> box("a:!!notbase64!!")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void hint() {
        assertThat(SecretBox.hint("sk-proj-abcdef1234a91F")).isEqualTo("sk-…a91F");
        assertThat(SecretBox.hint("short")).isEqualTo("••••");
        assertThat(SecretBox.hint(null)).isNull();
    }
}
