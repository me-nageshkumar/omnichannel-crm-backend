package me.nageshk.omnichannel.user;

/** Platform roles. Hierarchy (future): SUPER_ADMIN creates APP_ADMINs, who create customer ADMINs, who create USERs. */
public enum UserRole {
    SUPER_ADMIN,
    APP_ADMIN,
    ADMIN,
    USER;

    /** Spring Security authority name, e.g. {@code ROLE_SUPER_ADMIN}. */
    public String authority() {
        return "ROLE_" + name();
    }
}
