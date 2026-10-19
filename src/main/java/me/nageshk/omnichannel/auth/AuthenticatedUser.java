package me.nageshk.omnichannel.auth;

import java.util.Collection;
import java.util.List;
import me.nageshk.omnichannel.user.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** Spring Security view of a {@link User}; the principal on every authenticated request. */
public class AuthenticatedUser implements UserDetails {

    private final String userId;
    private final String username;
    private final String password;
    private final String roleId;
    private final boolean active;
    private final boolean twoFactorEnabled;
    private final String totpSecret;
    private final long tokenVersion;

    public AuthenticatedUser(User user) {
        this.userId = user.getId();
        this.username = user.getUsername();
        this.password = user.getPassword();
        this.roleId = user.getRoleId();
        this.active = user.isActive();
        this.twoFactorEnabled = user.getTwoFactor() != null && user.getTwoFactor().enabled();
        this.totpSecret = user.getTwoFactor() == null ? null : user.getTwoFactor().totpSecret();
        this.tokenVersion = user.getTokenVersion();
    }

    public String userId() { return userId; }
    public String roleId() { return roleId; }
    public boolean twoFactorEnabled() { return twoFactorEnabled; }
    public String totpSecret() { return totpSecret; }
    public long tokenVersion() { return tokenVersion; }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // Role-derived authorities arrive with the role cache; nothing checks them yet.
        return List.of();
    }

    @Override public String getPassword() { return password; }
    @Override public String getUsername() { return username; }
    @Override public boolean isEnabled() { return active; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }

    @Override
    public String toString() {
        return "AuthenticatedUser[" + username + ", " + roleId + "]";
    }
}
