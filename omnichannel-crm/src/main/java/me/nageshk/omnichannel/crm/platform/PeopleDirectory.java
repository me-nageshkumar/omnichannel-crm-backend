package me.nageshk.omnichannel.crm.platform;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Users and their teams. */
public interface PeopleDirectory {

    Set<String> teamIdsOf(String workspaceId, String userId);

    /** Whether the user works in the brand (a brand role, or a company role in its customer) and is not disabled. */
    boolean isBrandUser(String workspaceId, String userId);

    /** The existing users by id; missing ids are absent. */
    Map<String, PlatformPerson> people(Collection<String> userIds);

    Optional<PlatformPerson> byUsername(String username);

    /**
     * The user's effective authorities in the brand; empty when the user does not exist, is disabled (everywhere or
     * for the brand) or has no access to the brand at all.
     */
    Optional<Set<String>> authoritiesIn(String workspaceId, String userId);
}
