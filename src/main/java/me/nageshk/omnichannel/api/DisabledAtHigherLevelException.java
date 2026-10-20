package me.nageshk.omnichannel.api;

import me.nageshk.omnichannel.enablement.EnableLevel;

/** Enabling something disabled at a higher level than the actor's: 403 {@code DISABLED_AT_HIGHER_LEVEL}. */
public class DisabledAtHigherLevelException extends RuntimeException {

    public static final String CODE = "DISABLED_AT_HIGHER_LEVEL";

    private final EnableLevel level;

    public DisabledAtHigherLevelException(EnableLevel level) {
        super(switch (level) {
            case PLATFORM -> "Disabled by platform staff. Only platform staff can enable it.";
            case COMPANY -> "Disabled by a Customer Admin. Only a Customer Admin or platform staff can enable it.";
            case BRAND -> "Disabled by a brand manager.";
        });
        this.level = level;
    }

    public EnableLevel level() { return level; }
}
