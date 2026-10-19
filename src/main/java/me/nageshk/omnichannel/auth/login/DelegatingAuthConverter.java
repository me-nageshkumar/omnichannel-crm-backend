package me.nageshk.omnichannel.auth.login;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.stereotype.Component;

/** First converter that recognises the request wins; none → null and the filter passes the request on. */
@Component
public class DelegatingAuthConverter implements AuthenticationConverter {

    private final List<AuthenticationConverter> converters;

    public DelegatingAuthConverter(PasswordAuthConverter password) {
        this.converters = List.of(password);
    }

    @Override
    public Authentication convert(HttpServletRequest request) {
        for (AuthenticationConverter c : converters) {
            Authentication auth = c.convert(request);
            if (auth != null) return auth;
        }
        return null;
    }
}
