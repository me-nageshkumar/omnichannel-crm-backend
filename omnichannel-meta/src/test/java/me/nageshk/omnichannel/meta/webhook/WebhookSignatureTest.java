package me.nageshk.omnichannel.meta.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class WebhookSignatureTest {

    private static final String SECRET = "app-secret";
    private static final byte[] BODY = "{\"object\":\"whatsapp_business_account\"}".getBytes(StandardCharsets.UTF_8);

    static String sign(byte[] body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder hex = new StringBuilder();
        for (byte b : mac.doFinal(body)) hex.append(String.format("%02x", b));
        return "sha256=" + hex;
    }

    @Test
    void validSignature() throws Exception {
        assertThat(WebhookSignature.valid(BODY, sign(BODY, SECRET), SECRET)).isTrue();
    }

    @Test
    void uppercaseHexAccepted() throws Exception {
        String s = sign(BODY, SECRET);
        assertThat(WebhookSignature.valid(BODY, "sha256=" + s.substring(7).toUpperCase(), SECRET)).isTrue();
    }

    @Test
    void missingHeader() {
        assertThat(WebhookSignature.valid(BODY, null, SECRET)).isFalse();
        assertThat(WebhookSignature.valid(BODY, "", SECRET)).isFalse();
    }

    @Test
    void wrongPrefix() throws Exception {
        assertThat(WebhookSignature.valid(BODY, sign(BODY, SECRET).replace("sha256=", "sha1="), SECRET)).isFalse();
    }

    @Test
    void wrongSecret() throws Exception {
        assertThat(WebhookSignature.valid(BODY, sign(BODY, "other"), SECRET)).isFalse();
    }

    @Test
    void oneByteChanged() throws Exception {
        String s = sign(BODY, SECRET);
        byte[] changed = BODY.clone();
        changed[3] = (byte) (changed[3] + 1);
        assertThat(WebhookSignature.valid(changed, s, SECRET)).isFalse();
    }

    @Test
    void notHex() {
        assertThat(WebhookSignature.valid(BODY, "sha256=zz", SECRET)).isFalse();
    }
}
