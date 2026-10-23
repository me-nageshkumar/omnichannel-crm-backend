package me.nageshk.omnichannel.crm.platform;

import me.nageshk.omnichannel.security.auth.AuthenticatedUser;

/** The platform's enable/disable audit trail. */
public interface EnablementAudit {

    void recordCrmSwitch(AuthenticatedUser by, String workspaceId, boolean before, boolean after);
}
