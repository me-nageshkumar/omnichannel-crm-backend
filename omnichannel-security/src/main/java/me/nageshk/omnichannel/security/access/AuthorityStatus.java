package me.nageshk.omnichannel.security.access;

import me.nageshk.omnichannel.db.access.AuthorityDefinition;
import me.nageshk.omnichannel.db.access.RoleLevel;
import me.nageshk.omnichannel.security.access.AuthorityCheckScanner.CheckKind;
import me.nageshk.omnichannel.security.access.AuthorityCheckScanner.FoundCheck;

/** Whether a created authority is checked in code with a kind of check that can pass at its level. */
public enum AuthorityStatus {
    ACTIVE, NOT_CHECKED, WRONG_LEVEL;

    /** {@code found} is the scan result for the code, or null when no {@code @PreAuthorize} checks it. */
    public static AuthorityStatus statusOf(AuthorityDefinition d, FoundCheck found) {
        if (d.isBuiltIn()) return ACTIVE;
        if (found == null) return NOT_CHECKED;
        if (d.getLevel() == RoleLevel.BRAND && !found.kinds().contains(CheckKind.BRAND)) return WRONG_LEVEL;
        if (d.getLevel() == RoleLevel.PLATFORM && !found.kinds().contains(CheckKind.GLOBAL)) return WRONG_LEVEL;
        return ACTIVE;
    }
}
