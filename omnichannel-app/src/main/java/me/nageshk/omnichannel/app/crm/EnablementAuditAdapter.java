package me.nageshk.omnichannel.app.crm;

import java.time.Clock;
import me.nageshk.omnichannel.common.enablement.EnableLevel;
import me.nageshk.omnichannel.crm.platform.EnablementAudit;
import me.nageshk.omnichannel.db.enablement.EnablementChange;
import me.nageshk.omnichannel.db.enablement.EnablementChangeRepository;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import org.springframework.stereotype.Component;

@Component
class EnablementAuditAdapter implements EnablementAudit {

    private final EnablementChangeRepository changes;
    private final Clock clock;

    EnablementAuditAdapter(EnablementChangeRepository changes, Clock clock) {
        this.changes = changes;
        this.clock = clock;
    }

    @Override
    public void recordCrmSwitch(AuthenticatedUser by, String workspaceId, boolean before, boolean after) {
        EnablementChange c = new EnablementChange();
        c.setTargetType("crm");
        c.setTargetId(workspaceId);
        c.setEnabledBefore(before);
        c.setEnabledAfter(after);
        c.setBy(by.getUsername());
        c.setLevel(EnableLevel.PLATFORM);
        c.setAt(clock.instant());
        changes.save(c);
    }
}
