package me.nageshk.omnichannel.meta.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Meta's {@code X-Hub-Signature-256}: {@code sha256=<hex HMAC-SHA256 of the raw body, keyed with the app secret>}. */
public final class WebhookSignature {

    private static final String PREFIX = "sha256=";

    private WebhookSignature() {}

    /** Compared in constant time; any malformed header is simply not valid. */
    public static boolean valid(byte[] body, String header, String appSecret) {
        if (header == null || appSecret == null || !header.startsWith(PREFIX)) return false;
        byte[] given;
        try {
            given = HexFormat.of().parseHex(header.substring(PREFIX.length()).toLowerCase());
        } catch (IllegalArgumentException e) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return MessageDigest.isEqual(mac.doFinal(body), given);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }
}
