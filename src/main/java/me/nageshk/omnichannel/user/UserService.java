package me.nageshk.omnichannel.user;

import me.nageshk.omnichannel.enablement.Disabled;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import me.nageshk.omnichannel.access.AccessService;
import me.nageshk.omnichannel.access.BuiltInRoles;
import me.nageshk.omnichannel.access.RoleCache;
import me.nageshk.omnichannel.access.RoleLevel;
import me.nageshk.omnichannel.auth.AccountLockedException;
import me.nageshk.omnichannel.auth.AuthenticatedUser;
import me.nageshk.omnichannel.auth.LoginAttemptService;
import me.nageshk.omnichannel.auth.TotpService;
import me.nageshk.omnichannel.tenant.CustomerRepository;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** Self-service account operations for the logged-in user. */
@Service
public class UserService {

    public record TwoFactorSetup(String secret, String qrCodeDataUri, String issuer, String username) {}

    /** The caller's own profile; never carries the password, token version or TOTP secret. */
    public record CurrentUser(
            String id,
            String name,
            String username,
            String email,
            String mobileNumber,
            boolean twoFactorEnabled,
            boolean superAdmin,
            CustomerRef customer,
            RoleRef role,
            Set<String> authorities,
            List<WorkspaceAccess> workspaces,
            boolean mustChangePassword) {}

    public record CustomerRef(String id, String name) {}

    public record RoleRef(String id, String name) {}

    /** A workspace the user can act in; {@code role} is null when access comes from the COMPANY role. */
    public record WorkspaceAccess(String id, String name, RoleRef role, Set<String> authorities, Disabled disabled) {}

    private final UserRepository users;
    private final AccessService access;
    private final RoleCache roles;
    private final CustomerRepository customers;
    private final TotpService totp;
    private final LoginAttemptService attempts;

    public UserService(UserRepository users, AccessService access, RoleCache roles, CustomerRepository customers,
            TotpService totp, LoginAttemptService attempts) {
        this.users = users;
        this.access = access;
        this.roles = roles;
        this.customers = customers;
        this.totp = totp;
        this.attempts = attempts;
    }

    public CurrentUser currentUser(String username) {
        User u = require(username);
        AuthenticatedUser principal = access.load(u);
        Set<String> authorities = new TreeSet<>(principal.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(code -> !BuiltInRoles.SUPER_ADMIN.equals(code))
                .collect(Collectors.toSet()));
        CustomerRef customer = u.getCustomerId() == null ? null
                : customers.findById(u.getCustomerId()).map(c -> new CustomerRef(c.getId(), c.getName())).orElse(null);
        boolean companyWide = roleRef(u.getRoleId(), RoleLevel.COMPANY) != null;
        List<WorkspaceAccess> workspaces = access.workspacesOf(principal).stream()
                .map(w -> new WorkspaceAccess(
                        w.getId(),
                        w.getName(),
                        companyWide ? null : workspaceRole(u, w.getId()),
                        new TreeSet<>(access.inWorkspace(principal, w.getId())),
                        w.getDisabled()))
                .toList();
        return new CurrentUser(
                u.getId(), u.getName(), u.getUsername(), u.getEmail(), u.getMobileNumber(),
                u.getTwoFactor().enabled(),
                principal.superAdmin(),
                customer,
                roleRef(u.getRoleId(), null),
                authorities,
                workspaces,
                u.isMustChangePassword());
    }

    /** A fresh secret and QR code; nothing is stored until {@link #confirmTwoFactor}. */
    public TwoFactorSetup initiateTwoFactor(String username) {
        User user = require(username);
        if (user.getTwoFactor().enabled()) {
            throw new TwoFactorStateException("two-factor authentication is already enabled");
        }
        String secret = totp.generateSecret();
        return new TwoFactorSetup(secret, totp.qrCodeDataUri(secret, username), totp.issuer(), username);
    }

    public void confirmTwoFactor(String username, String secret, String otp) {
        User user = require(username);
        if (user.getTwoFactor().enabled()) {
            throw new TwoFactorStateException("two-factor authentication is already enabled");
        }
        verifyWithLockout(username, secret, otp);
        user.setTwoFactor(TwoFactor.enabledWith(secret));
        users.save(user);
    }

    public void disableTwoFactor(String username, String otp) {
        User user = require(username);
        if (!user.getTwoFactor().enabled()) {
            throw new TwoFactorStateException("two-factor authentication is already disabled");
        }
        verifyWithLockout(username, user.getTwoFactor().totpSecret(), otp);
        user.setTwoFactor(TwoFactor.disabled());
        users.save(user);
    }

    private void verifyWithLockout(String username, String secret, String otp) {
        if (attempts.isOtpBlocked(username)) {
            throw new AccountLockedException("too many failed OTP attempts", attempts.otpBlockRemainingSeconds(username));
        }
        if (!totp.verify(secret, otp)) {
            attempts.recordFailedOtp(username);
            throw new InvalidOtpException();
        }
        attempts.clearOtp(username);
    }

    private RoleRef workspaceRole(User u, String workspaceId) {
        return u.getWorkspaceRoles().stream()
                .filter(wr -> workspaceId.equals(wr.workspaceId()))
                .findFirst()
                .map(wr -> roleRef(wr.roleId(), RoleLevel.BRAND))
                .orElse(null);
    }

    /** The role as a reference, or null if the id is unset, unknown, or (when given) not at {@code level}. */
    private RoleRef roleRef(String roleId, RoleLevel level) {
        return roles.byId(roleId)
                .filter(r -> level == null || r.getLevel() == level)
                .map(r -> new RoleRef(r.getId(), r.getName()))
                .orElse(null);
    }

    private User require(String username) {
        return users.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("no user '" + username + "'"));
    }
}
