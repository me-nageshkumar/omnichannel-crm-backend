package me.nageshk.omnichannel.crm.platform;

import java.util.List;
import java.util.Set;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;

/** A brand's numbers and which of them a user can chat on. */
public interface NumberDirectory {

    /** The brand's numbers, ordered by number. */
    List<PlatformNumber> numbers(String workspaceId);

    /** Ids of the numbers the user can chat on: every number with {@code inbox:view-all}, else the team-owned ones. */
    Set<String> chattable(AuthenticatedUser me, String workspaceId);
}
