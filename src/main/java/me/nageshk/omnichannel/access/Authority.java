package me.nageshk.omnichannel.access;

import static me.nageshk.omnichannel.access.BuiltInRoles.*;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/** Every code-defined authority. New features add entries; they are never created from the console. */
public enum Authority {
    PLATFORM_USER_MANAGE("platform-user:manage", RoleLevel.PLATFORM,
            "Create, edit, deactivate platform users and give platform roles"),
    CUSTOMER_VIEW("customer:view", RoleLevel.PLATFORM, "See the customer list and details",
            ACCOUNT_MANAGER),
    CUSTOMER_CREATE("customer:create", RoleLevel.PLATFORM,
            "Create a customer with its owner and first workspace", ACCOUNT_MANAGER),
    CUSTOMER_MANAGE("customer:manage", RoleLevel.PLATFORM,
            "Edit, deactivate/reactivate a customer, add a Customer Admin, reset its password",
            ACCOUNT_MANAGER),
    WHATSAPP_BRIDGE("whatsapp:bridge", RoleLevel.PLATFORM,
            "Use the WhatsApp bridge endpoints and WebSocket", WHATSAPP_DEVICE),
    WORKSPACE_CREATE("workspace:create", RoleLevel.COMPANY, "Create workspaces", CUSTOMER_ADMIN),
    COMPANY_USER_MANAGE("company-user:manage", RoleLevel.COMPANY,
            "Deactivate/reactivate any user of the company", CUSTOMER_ADMIN),
    WORKSPACE_SETTINGS("workspace:settings", RoleLevel.BRAND,
            "Rename the workspace (later: hours, routing)", CUSTOMER_ADMIN, WORKSPACE_MANAGER),
    USER_VIEW("user:view", RoleLevel.BRAND, "See users with access to the workspace",
            CUSTOMER_ADMIN, WORKSPACE_MANAGER, TEAM_LEAD),
    USER_MANAGE("user:manage", RoleLevel.BRAND,
            "Create users in the workspace, give/change/remove their role here, reset their password",
            CUSTOMER_ADMIN, WORKSPACE_MANAGER),
    TEAM_MANAGE("team:manage", RoleLevel.BRAND, "Create and rename teams, change members",
            CUSTOMER_ADMIN, WORKSPACE_MANAGER);

    private final String code;
    private final RoleLevel level;
    private final String description;
    private final Set<String> defaultRoles;

    Authority(String code, RoleLevel level, String description, String... defaultRoles) {
        this.code = code;
        this.level = level;
        this.description = description;
        this.defaultRoles = Set.of(defaultRoles);
    }

    public String code() { return code; }
    public RoleLevel level() { return level; }
    public String description() { return description; }
    public Set<String> defaultRoles() { return defaultRoles; }

    public static Optional<Authority> fromCode(String code) {
        return Arrays.stream(values()).filter(a -> a.code.equals(code)).findFirst();
    }
}
