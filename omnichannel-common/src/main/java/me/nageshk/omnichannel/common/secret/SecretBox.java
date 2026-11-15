package me.nageshk.omnichannel.common.secret;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import me.nageshk.omnichannel.common.api.CodedException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Seals secrets (API keys) with AES-256-GCM. Keys come from configuration as {@code id:base64}; the first seals, all
 * open, so a key can be rotated by putting the new one first. Blank entries count as absent: with no key the box is
 * not ready and sealing is refused, but the server still starts. A malformed key fails startup.
 */
@Component
public class SecretBox {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, SecretKeySpec> keys = new LinkedHashMap<>();
    private String sealingId;

    public SecretBox(SecretsProperties props) {
        if (props == null || props.keys() == null) return;
        for (String entry : props.keys()) {
            if (entry == null || entry.isBlank()) continue;
            int colon = entry.indexOf(':');
            if (colon <= 0) throw new IllegalStateException("A secrets key must look like id:base64 (omnichannel.secrets.keys)");
            String id = entry.substring(0, colon).trim();
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(entry.substring(colon + 1).trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("The secrets key '" + id + "' is not valid base64");
            }
            if (bytes.length != 32) throw new IllegalStateException("The secrets key '" + id + "' must be 32 bytes");
            if (keys.putIfAbsent(id, new SecretKeySpec(bytes, "AES")) != null) {
                throw new IllegalStateException("The secrets key id '" + id + "' is used twice");
            }
            if (sealingId == null) sealingId = id;
        }
    }

    public boolean ready() {
        return sealingId != null;
    }

    public SealedSecret seal(String plain) {
        if (!ready()) {
            throw new CodedException(HttpStatus.SERVICE_UNAVAILABLE, "secrets_unavailable", null,
                    "Secrets cannot be saved: the server has no encryption key configured");
        }
        byte[] iv = new byte[12];
        RANDOM.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(sealingId), new GCMParameterSpec(128, iv));
            byte[] out = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return new SealedSecret(sealingId, Base64.getEncoder().encodeToString(iv), Base64.getEncoder().encodeToString(out));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not seal the secret", e);
        }
    }

    public String open(SealedSecret s) {
        SecretKeySpec key = keys.get(s.keyId());
        if (key == null) throw new IllegalStateException("No secrets key '" + s.keyId() + "' is configured");
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Base64.getDecoder().decode(s.iv())));
            return new String(cipher.doFinal(Base64.getDecoder().decode(s.ciphertext())), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Could not open the secret", e);
        }
    }

    /** What may be shown of a secret: its first three characters and last four, or dots when it is short. */
    public static String hint(String plain) {
        if (plain == null) return null;
        if (plain.length() < 10) return "••••";
        return plain.substring(0, 3) + "…" + plain.substring(plain.length() - 4);
    }
}
