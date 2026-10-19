package me.nageshk.omnichannel.auth.login;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.stereotype.Component;

/** Turns {@code POST /api/auth/verify-otp} (bearer pending token + {@code {otp}}) into an {@link OtpAuthentication}. */
@Component
public class OtpAuthConverter implements AuthenticationConverter {

    public static final String VERIFY_OTP_PATH = "/api/auth/verify-otp";
    private static final String BEARER = "Bearer ";

    private final ObjectMapper mapper;

    public OtpAuthConverter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Authentication convert(HttpServletRequest request) {
        if (!HttpMethod.POST.matches(request.getMethod()) || !VERIFY_OTP_PATH.equals(request.getRequestURI())) {
            return null;
        }
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER) || header.length() <= BEARER.length()) {
            throw new BadCredentialsException(LoginFailureHandler.INVALID_TOKEN);
        }
        LoginRequest body;
        try {
            body = mapper.readValue(request.getInputStream(), LoginRequest.class);
        } catch (IOException e) {
            throw new AuthenticationServiceException("malformed verify-otp request", e);
        }
        if (body == null || body.otp() == null || body.otp().isBlank()) {
            throw new BadCredentialsException(LoginFailureHandler.INVALID_OTP);
        }
        return OtpAuthentication.unauthenticated(header.substring(BEARER.length()), body.otp());
    }
}
