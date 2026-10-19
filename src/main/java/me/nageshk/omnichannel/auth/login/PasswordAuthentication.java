package me.nageshk.omnichannel.auth.login;

import java.util.Collection;
import java.util.List;
import me.nageshk.omnichannel.auth.AuthenticatedUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/** Step one of login. Unauthenticated: username + raw password. Authenticated: username + the loaded user as details. */
public class PasswordAuthentication implements Authentication {

    private final String username;
    private final String password;
    private final AuthenticatedUser details;
    private boolean authenticated;

    public static PasswordAuthentication unauthenticated(String username, String password) {
        return new PasswordAuthentication(username, password, null, false);
    }

    public static PasswordAuthentication authenticated(AuthenticatedUser user) {
        return new PasswordAuthentication(user.getUsername(), null, user, true);
    }

    private PasswordAuthentication(String username, String password, AuthenticatedUser details, boolean authenticated) {
        this.username = username;
        this.password = password;
        this.details = details;
        this.authenticated = authenticated;
    }

    @Override public Object getPrincipal() { return username; }
    @Override public Object getCredentials() { return password; }
    @Override public AuthenticatedUser getDetails() { return details; }
    @Override public String getName() { return username; }
    @Override public boolean isAuthenticated() { return authenticated; }
    @Override public void setAuthenticated(boolean authenticated) { this.authenticated = authenticated; }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return details == null ? List.of() : details.getAuthorities();
    }
}
