package me.nageshk.omnichannel.auth;

import java.util.Collection;
import java.util.List;
import me.nageshk.omnichannel.user.User;
import me.nageshk.omnichannel.user.UserRole;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** Spring Security view of a {@link User}; the principal on every authenticated request. */
public class AuthenticatedUser implements UserDetails {

    private final String userId;
    private final String username;
    private final String password;
    private final UserRole role;
    private final boolean active;
    private final boolean twoFactorEnabled;
    private final String totpSecret;
    private final long tokenVersion;

    public AuthenticatedUser(User user) {
        this.userId = user.getId();
        this.username = user.getUsername();
        this.password = user.getPassword();
        this.role = user.getRole();
        this.active = user.isActive();
        this.twoFactorEnabled = user.getTwoFactor() != null && user.getTwoFactor().enabled();
        this.totpSecret = user.getTwoFactor() == null ? null : user.getTwoFactor().totpSecret();
        this.tokenVersion = user.getTokenVersion();
    }

    public String userId() { return userId; }
    public UserRole role() { return role; }
    public boolean twoFactorEnabled() { return twoFactorEnabled; }
    public String totpSecret() { return totpSecret; }
    public long tokenVersion() { return tokenVersion; }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(role.authority()));
    }

    @Override public String getPassword() { return password; }
    @Override public String getUsername() { return username; }
    @Override public boolean isEnabled() { return active; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }

    @Override
    public String toString() {
        return "AuthenticatedUser[" + username + ", " + role + "]";
    }
}
