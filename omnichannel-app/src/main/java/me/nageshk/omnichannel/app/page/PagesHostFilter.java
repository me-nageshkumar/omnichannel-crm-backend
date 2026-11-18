package me.nageshk.omnichannel.app.page;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import me.nageshk.omnichannel.service.page.PagesAddresses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A customer's domain serves the message and form pages only (spec 2026-11-18 link addresses §5.7): the console,
 * the API and everything else answer 404 with no body there. Other hosts are untouched.
 */
public class PagesHostFilter extends OncePerRequestFilter {

    private final PagesAddresses addresses;
    private final boolean trustForwardedHost;

    public PagesHostFilter(PagesAddresses addresses, boolean trustForwardedHost) {
        this.addresses = addresses;
        this.trustForwardedHost = trustForwardedHost;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String host = host(req);
        if (host != null && addresses.customerHost(host) && !pagesOnly(req)) {
            res.setStatus(HttpServletResponse.SC_NOT_FOUND);
            res.setContentLength(0);
            return;
        }
        chain.doFilter(req, res);
    }

    private String host(HttpServletRequest req) {
        if (trustForwardedHost) {
            String forwarded = req.getHeader("X-Forwarded-Host");
            if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].strip();
        }
        String h = req.getHeader("Host");
        return h != null && !h.isBlank() ? h : req.getServerName();
    }

    private static boolean pagesOnly(HttpServletRequest req) {
        String m = req.getMethod();
        if (!"GET".equals(m) && !"POST".equals(m) && !"HEAD".equals(m)) return false;
        String path = req.getRequestURI().substring(req.getContextPath().length());
        return path.startsWith("/t/") || path.startsWith("/f/");
    }

    /** Before everything else, Spring Security included. */
    @Configuration
    static class Registration {
        @Bean
        FilterRegistrationBean<PagesHostFilter> pagesHostFilter(PagesAddresses addresses,
                @Value("${omnichannel.pages.trust-forwarded-host:false}") boolean trustForwardedHost) {
            FilterRegistrationBean<PagesHostFilter> bean = new FilterRegistrationBean<>(
                    new PagesHostFilter(addresses, trustForwardedHost));
            bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
            bean.addUrlPatterns("/*");
            return bean;
        }
    }
}
