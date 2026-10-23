package me.nageshk.omnichannel.security.user;

import java.util.List;
import me.nageshk.omnichannel.db.enablement.Disabled;
import me.nageshk.omnichannel.security.user.UserService.RoleRef;

/**
 * A user as management pages show it. Never carries the password, token version or TOTP secret.
 *
 * @param role the PLATFORM or COMPANY role, or null
 * @param workspaceRoles the BRAND role in each workspace
 */
public record UserSummary(
        String id,
        String name,
        String username,
        String email,
        String mobileNumber,
        boolean active,
        Disabled disabled,
        RoleRef role,
        List<WorkspaceRoleView> workspaceRoles,
        boolean mustChangePassword) {

    public record WorkspaceRoleView(String workspaceId, String workspaceName, RoleRef role) {}
}
