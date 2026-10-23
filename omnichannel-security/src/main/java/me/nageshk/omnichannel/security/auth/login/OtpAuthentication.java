package me.nageshk.omnichannel.security.auth.login;

import java.util.Collection;
import java.util.List;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/**
 * Step two of login. Unauthenticated: the pending token as principal plus the OTP. Authenticated:
 * the username as principal and the loaded user as details.
 */
public record OtpAuthentication(String principalName, String otp, AuthenticatedUser details) implements Authentication {

    public static OtpAuthentication unauthenticated(String pendingToken, String otp) {
        return new OtpAuthentication(pendingToken, otp, null);
    }

    public static OtpAuthentication authenticated(AuthenticatedUser user) {
        return new OtpAuthentication(user.getUsername(), null, user);
    }

    @Override public Object getPrincipal() { return principalName; }
    @Override public Object getCredentials() { return otp; }
    @Override public AuthenticatedUser getDetails() { return details; }
    @Override public String getName() { return principalName; }
    @Override public boolean isAuthenticated() { return details != null; }
    @Override public void setAuthenticated(boolean isAuthenticated) {}

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return details == null ? List.of() : details.getAuthorities();
    }
}
