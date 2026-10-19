package me.nageshk.omnichannel.auth.login;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import me.nageshk.omnichannel.auth.AccountLockedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

/** Maps login failures to {@code { error }} bodies: 429 for lockout, 401 for everything else. */
@Component
public class LoginFailureHandler implements AuthenticationFailureHandler {

    public static final String INVALID_CREDENTIALS = "invalid_credentials";
    public static final String ACCOUNT_INACTIVE = "account_inactive";
    public static final String INVALID_TOKEN = "invalid_token";
    public static final String INVALID_OTP = "invalid_otp";
    public static final String TOO_MANY_ATTEMPTS = "too_many_attempts";

    private final ObjectMapper mapper;

    public LoginFailureHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException ex) throws IOException {
        HttpStatus status = HttpStatus.UNAUTHORIZED;
        Map<String, Object> body = new LinkedHashMap<>();
        if (ex instanceof AccountLockedException locked) {
            status = HttpStatus.TOO_MANY_REQUESTS;
            body.put("error", TOO_MANY_ATTEMPTS);
            body.put("retryAfterSeconds", locked.retryAfterSeconds());
        } else if (ex instanceof DisabledException) {
            body.put("error", ACCOUNT_INACTIVE);
        } else if (ex instanceof CredentialsExpiredException) {
            body.put("error", INVALID_TOKEN);
        } else if (ex instanceof BadCredentialsException
                && (INVALID_OTP.equals(ex.getMessage()) || INVALID_TOKEN.equals(ex.getMessage()))) {
            body.put("error", ex.getMessage());
        } else {
            body.put("error", INVALID_CREDENTIALS);
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(response.getWriter(), body);
    }
}
