package me.nageshk.omnichannel.security.enablement;

import java.time.Clock;
import java.time.Instant;
import me.nageshk.omnichannel.common.api.DisabledAtHigherLevelException;
import me.nageshk.omnichannel.common.enablement.EnableLevel;
import me.nageshk.omnichannel.db.enablement.Disabled;
import me.nageshk.omnichannel.db.enablement.EnablementChange;
import me.nageshk.omnichannel.db.enablement.EnablementChangeRepository;
import me.nageshk.omnichannel.security.access.AccessService;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import org.springframework.stereotype.Service;

/**
 * The enable/disable rule shared by numbers, brands, customers, logins and brand access: anyone with
 * the authority may disable, but enabling needs a level at least that of the stored disable. The
 * level is that of the role the actor holds the authority through, not the authority's own level.
 */
@Service
public class Enablement {

    private final AccessService access;
    private final EnablementChangeRepository changes;
    private final Clock clock;

    public Enablement(AccessService access, EnablementChangeRepository changes, Clock clock) {
        this.access = access;
        this.changes = changes;
        this.clock = clock;
    }

    /** PLATFORM for platform staff; COMPANY when the COMPANY role holds {@code authority}; else BRAND. */
    public EnableLevel levelOf(AuthenticatedUser actor, String authority, String workspaceId) {
        if (actor.customerId() == null) return EnableLevel.PLATFORM;
        if (access.companyCodes(actor).contains(authority)) return EnableLevel.COMPANY;
        return EnableLevel.BRAND;
    }

    /** The record to store when {@code actor} disables at {@code level}: a strictly higher one is kept. */
    public Disabled disable(Disabled current, AuthenticatedUser actor, EnableLevel level) {
        if (current != null && current.level().compareTo(level) > 0) return current;
        return new Disabled(actor.getUsername(), level, Instant.now(clock));
    }

    /** @throws DisabledAtHigherLevelException when the stored disable is above {@code level} */
    public void checkEnable(Disabled current, EnableLevel level) {
        if (current != null && !level.atLeast(current.level())) {
            throw new DisabledAtHigherLevelException(current.level());
        }
    }

    public void record(String targetType, String targetId, boolean enabledBefore, boolean enabledAfter,
            AuthenticatedUser actor, EnableLevel level) {
        EnablementChange c = new EnablementChange();
        c.setTargetType(targetType);
        c.setTargetId(targetId);
        c.setEnabledBefore(enabledBefore);
        c.setEnabledAfter(enabledAfter);
        c.setBy(actor.getUsername());
        c.setLevel(level);
        c.setAt(Instant.now(clock));
        changes.insert(c);
    }
}
