package me.nageshk.omnichannel.security.auth;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import me.nageshk.omnichannel.db.user.BrandDisable;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.db.user.WorkspaceRole;
import me.nageshk.omnichannel.security.access.BuiltInRoles;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Spring Security view of a {@link User}; the principal on every authenticated request. Holds the
 * user's global (PLATFORM or COMPANY) authorities; BRAND authorities are resolved per workspace by
 * {@code AccessService}. Built by {@code AccessService.load}.
 */
public class AuthenticatedUser implements UserDetails {

    private final String userId;
    private final String username;
    private final String password;
    private final String customerId;
    private final String roleId;
    private final List<WorkspaceRole> workspaceRoles;
    private final Set<String> brandDisabled;
    private final boolean active;
    private final boolean mustChangePassword;
    private final boolean twoFactorEnabled;
    private final String totpSecret;
    private final long tokenVersion;
    private final Set<String> grantedAuthorities;
    private final List<SimpleGrantedAuthority> authorities;

    /** For a user whose customer, if any, is active. */
    public AuthenticatedUser(User user, Set<String> grantedAuthorities) {
        this(user, grantedAuthorities, true);
    }

    /** {@code customerActive} false makes the user disabled, exactly as an inactive user. */
    public AuthenticatedUser(User user, Set<String> grantedAuthorities, boolean customerActive) {
        this.userId = user.getId();
        this.username = user.getUsername();
        this.password = user.getPassword();
        this.customerId = user.getCustomerId();
        this.roleId = user.getRoleId();
        this.workspaceRoles = List.copyOf(user.getWorkspaceRoles());
        this.brandDisabled = user.getBrandDisables().stream().map(BrandDisable::workspaceId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.active = user.isActive() && customerActive;
        this.mustChangePassword = user.isMustChangePassword();
        this.twoFactorEnabled = user.getTwoFactor() != null && user.getTwoFactor().enabled();
        this.totpSecret = user.getTwoFactor() == null ? null : user.getTwoFactor().totpSecret();
        this.tokenVersion = user.getTokenVersion();
        this.grantedAuthorities = Set.copyOf(grantedAuthorities);
        this.authorities = this.grantedAuthorities.stream().map(SimpleGrantedAuthority::new).toList();
    }

    public String userId() { return userId; }
    public String customerId() { return customerId; }
    public String roleId() { return roleId; }
    public List<WorkspaceRole> workspaceRoles() { return workspaceRoles; }
    /** Whether the user's access to this workspace was switched off. */
    public boolean brandDisabled(String workspaceId) { return brandDisabled.contains(workspaceId); }
    public boolean superAdmin() { return grantedAuthorities.contains(BuiltInRoles.SUPER_ADMIN); }
    public boolean mustChangePassword() { return mustChangePassword; }
    public boolean twoFactorEnabled() { return twoFactorEnabled; }
    public String totpSecret() { return totpSecret; }
    public long tokenVersion() { return tokenVersion; }

    public boolean hasAuthority(String code) {
        return grantedAuthorities.contains(code);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
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
