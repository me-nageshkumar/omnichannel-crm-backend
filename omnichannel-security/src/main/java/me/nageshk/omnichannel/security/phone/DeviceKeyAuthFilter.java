package me.nageshk.omnichannel.security.phone;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Authenticates {@code Authorization: Device <key>}; the principal is a {@link DevicePrincipal} with authority {@code DEVICE}. */
@Component
public class DeviceKeyAuthFilter extends OncePerRequestFilter {

    public static final String DEVICE = "DEVICE";
    private static final String SCHEME = "Device ";

    private final DeviceKeys keys;

    public DeviceKeyAuthFilter(DeviceKeys keys) {
        this.keys = keys;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(SCHEME)) {
            keys.authenticate(header.substring(SCHEME.length()).trim()).ifPresent(device ->
                    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                            device, null, List.of(new SimpleGrantedAuthority(DEVICE)))));
        }
        chain.doFilter(request, response);
    }
}
