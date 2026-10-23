package me.nageshk.omnichannel.security.access;

import me.nageshk.omnichannel.db.access.RoleLevel;

/** Names of the roles the seeder creates, and the level each lives at. */
public final class BuiltInRoles {

    public static final String SUPER_ADMIN = "SUPER_ADMIN";
    public static final String ACCOUNT_MANAGER = "ACCOUNT_MANAGER";
    public static final String CUSTOMER_ADMIN = "CUSTOMER_ADMIN";
    public static final String WORKSPACE_MANAGER = "WORKSPACE_MANAGER";
    public static final String AGENT = "AGENT";

    private BuiltInRoles() {}

    public static RoleLevel levelOf(String name) {
        return switch (name) {
            case SUPER_ADMIN, ACCOUNT_MANAGER -> RoleLevel.PLATFORM;
            case CUSTOMER_ADMIN -> RoleLevel.COMPANY;
            default -> RoleLevel.BRAND;
        };
    }
}
