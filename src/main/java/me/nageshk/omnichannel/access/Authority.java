package me.nageshk.omnichannel.access;

import static me.nageshk.omnichannel.access.BuiltInRoles.*;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/** The built-in authorities: the seed list, and constants for the codes server logic refers to by name. Console-created authorities live only in the {@code authorities} collection. */
public enum Authority {
    PLATFORM_USER_MANAGE("platform-user:manage", RoleLevel.PLATFORM,
            "Create, edit, disable/enable platform users and give platform roles"),
    CUSTOMER_VIEW("customer:view", RoleLevel.PLATFORM, "See the customer list and details",
            ACCOUNT_MANAGER),
    CUSTOMER_CREATE("customer:create", RoleLevel.PLATFORM,
            "Create a customer with its owner and first workspace", ACCOUNT_MANAGER),
    CUSTOMER_MANAGE("customer:manage", RoleLevel.PLATFORM,
            "Edit, disable/enable a customer and its users, add a Customer Admin, reset its password",
            ACCOUNT_MANAGER),
    PHONE_NUMBER_VIEW_ALL("phone-number:view-all", RoleLevel.PLATFORM,
            "See every WhatsApp number on the platform", ACCOUNT_MANAGER),
    PHONE_NUMBER_MANAGE_ALL("phone-number:manage-all", RoleLevel.PLATFORM,
            "Disable/enable and delete any WhatsApp number", ACCOUNT_MANAGER),
    WHATSAPP_BRIDGE("whatsapp:bridge", RoleLevel.PLATFORM,
            "Use the WhatsApp bridge endpoints and WebSocket", WHATSAPP_DEVICE),
    WORKSPACE_CREATE("workspace:create", RoleLevel.COMPANY, "Create workspaces", CUSTOMER_ADMIN),
    COMPANY_USER_MANAGE("company-user:manage", RoleLevel.COMPANY,
            "Disable/enable any user of the company", CUSTOMER_ADMIN),
    WORKSPACE_ENABLE("workspace:enable", RoleLevel.COMPANY, "Disable or enable the company's brands",
            CUSTOMER_ADMIN),
    WORKSPACE_SETTINGS("workspace:settings", RoleLevel.BRAND,
            "Rename the workspace (later: hours, routing)", CUSTOMER_ADMIN, WORKSPACE_MANAGER),
    USER_VIEW("user:view", RoleLevel.BRAND, "See users with access to the workspace",
            CUSTOMER_ADMIN, WORKSPACE_MANAGER, TEAM_LEAD),
    USER_MANAGE("user:manage", RoleLevel.BRAND,
            "Create users in the workspace, give/change/remove their role here, reset their password",
            CUSTOMER_ADMIN, WORKSPACE_MANAGER),
    USER_ENABLE("user:enable", RoleLevel.BRAND, "Disable or enable a person's access to the brand",
            CUSTOMER_ADMIN, WORKSPACE_MANAGER),
    PHONE_NUMBER_VIEW("phone-number:view", RoleLevel.BRAND, "See the brand's WhatsApp numbers and their status",
            CUSTOMER_ADMIN, WORKSPACE_MANAGER, TEAM_LEAD),
    PHONE_NUMBER_MANAGE("phone-number:manage", RoleLevel.BRAND,
            "Add, label, connect, revoke, disable/enable and delete the brand's WhatsApp numbers",
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
    /** The part of the code before the colon. */
    public String group() { return code.substring(0, code.indexOf(':')); }
    public RoleLevel level() { return level; }
    public String description() { return description; }
    public Set<String> defaultRoles() { return defaultRoles; }

    public static Optional<Authority> fromCode(String code) {
        return Arrays.stream(values()).filter(a -> a.code.equals(code)).findFirst();
    }
}
