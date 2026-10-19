package me.nageshk.omnichannel.auth;

import dev.samstevens.totp.code.CodeVerifier;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.exceptions.QrGenerationException;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.qr.QrGenerator;
import dev.samstevens.totp.qr.ZxingPngQrGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.TimeProvider;
import java.util.Base64;
import me.nageshk.omnichannel.config.OmnichannelProperties;
import org.springframework.stereotype.Service;

/** Time-based one-time passwords: SHA1, six digits, 30-second period (authenticator-app defaults). */
@Service
public class TotpService {

    private final String issuer;
    private final SecretGenerator secrets = new DefaultSecretGenerator();
    private final QrGenerator qr = new ZxingPngQrGenerator();
    private final CodeVerifier verifier;

    public TotpService(OmnichannelProperties props, TimeProvider timeProvider) {
        this.issuer = props.twoFactor().issuer();
        this.verifier = new DefaultCodeVerifier(new DefaultCodeGenerator(), timeProvider);
    }

    public String issuer() {
        return issuer;
    }

    /** A fresh 32-character Base32 secret. */
    public String generateSecret() {
        return secrets.generate();
    }

    /** {@code data:image/png;base64,...} QR code an authenticator app can scan. */
    public String qrCodeDataUri(String secret, String username) {
        QrData data = new QrData.Builder().label(username).secret(secret).issuer(issuer).build();
        try {
            return "data:" + qr.getImageMimeType() + ";base64," + Base64.getEncoder().encodeToString(qr.generate(data));
        } catch (QrGenerationException e) {
            throw new IllegalStateException("could not render TOTP QR code", e);
        }
    }

    public boolean verify(String secret, String code) {
        if (secret == null || code == null || code.isBlank()) return false;
        return verifier.isValidCode(secret, code);
    }
}
