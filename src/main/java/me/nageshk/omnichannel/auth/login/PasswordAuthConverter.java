package me.nageshk.omnichannel.auth.login;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.stereotype.Component;

/** Turns {@code POST /api/auth/login} into an unauthenticated {@link PasswordAuthentication}. */
@Component
public class PasswordAuthConverter implements AuthenticationConverter {

    public static final String LOGIN_PATH = "/api/auth/login";

    private final ObjectMapper mapper;

    public PasswordAuthConverter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Authentication convert(HttpServletRequest request) {
        if (!HttpMethod.POST.matches(request.getMethod()) || !LOGIN_PATH.equals(request.getRequestURI())) {
            return null;
        }
        LoginRequest body;
        try {
            body = mapper.readValue(request.getInputStream(), LoginRequest.class);
        } catch (IOException | JacksonException e) {
            throw new AuthenticationServiceException("malformed login request", e);
        }
        if (body == null || isBlank(body.username()) || isBlank(body.password())) {
            throw new BadCredentialsException("username and password are required");
        }
        return PasswordAuthentication.unauthenticated(body.username(), body.password());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
