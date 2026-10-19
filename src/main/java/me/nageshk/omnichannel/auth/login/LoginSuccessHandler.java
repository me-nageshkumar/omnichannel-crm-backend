package me.nageshk.omnichannel.auth.login;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import me.nageshk.omnichannel.auth.AuthenticatedUser;
import me.nageshk.omnichannel.auth.JwtService;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * Writes the login reply. After a password with two-factor on: a pending token. Otherwise (password
 * without two-factor, or a verified OTP): the access token.
 */
@Component
public class LoginSuccessHandler implements AuthenticationSuccessHandler {

    private final JwtService jwt;
    private final ObjectMapper mapper;

    public LoginSuccessHandler(JwtService jwt, ObjectMapper mapper) {
        this.jwt = jwt;
        this.mapper = mapper;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain, Authentication authentication)
            throws IOException {
        onAuthenticationSuccess(request, response, authentication);
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException {
        AuthenticatedUser user = (AuthenticatedUser) authentication.getDetails();
        Map<String, Object> body;
        if (authentication instanceof PasswordAuthentication && user.twoFactorEnabled()) {
            body = Map.of("twoFactorRequired", true, "twoFactorToken", jwt.issuePendingToken(user.getUsername()));
        } else {
            JwtService.IssuedToken issued = jwt.issueAccessToken(user);
            body = Map.of("token", issued.token(), "expiresAt", issued.expiresAt().toString());
        }
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(response.getWriter(), body);
    }
}
