package me.nageshk.omnichannel.app.billing;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import me.nageshk.omnichannel.common.api.CodedException;
import me.nageshk.omnichannel.common.api.ReadOnlySafe;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.security.entitlement.Entitlements;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Read-only mode: once a customer's subscription has ended (after the grace period), or for a customer without a
 * plan when the platform says so, its users may read, search and export but change nothing. Every handler that is
 * not a GET is refused unless it carries {@link ReadOnlySafe}, so a new endpoint is read-only without anyone
 * remembering to make it so. Platform staff are never affected.
 */
@Component
public class ReadOnlyInterceptor implements HandlerInterceptor {

    public static final String CODE = "SUBSCRIPTION_EXPIRED";

    private static final Set<String> READS = Set.of("GET", "HEAD", "OPTIONS");

    private final Entitlements entitlements;

    public ReadOnlyInterceptor(Entitlements entitlements) {
        this.entitlements = entitlements;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (READS.contains(request.getMethod())) return true;
        if (!(handler instanceof HandlerMethod method) || method.hasMethodAnnotation(ReadOnlySafe.class)) return true;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthenticatedUser user) || user.customerId() == null) {
            return true;
        }
        if (entitlements.of(user.customerId()).readOnly()) {
            throw new CodedException(HttpStatus.PAYMENT_REQUIRED, CODE, null,
                    "The subscription has ended; the account is read-only until it is renewed");
        }
        return true;
    }
}
