package me.nageshk.omnichannel.service.campaign;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;

/**
 * Port to the CRM's segments (spec D C12): the host never imports the CRM module, so a campaign with a SEGMENT
 * audience reaches it through this interface, implemented in the application module.
 */
public interface SegmentAudiences {

    /** A segment by id and name. */
    record SegmentRef(String id, String name) {}

    /**
     * The audience resolved for one user: distinct host contact ids in member order, each contact's channel account,
     * and the resolution's record counts and cap.
     */
    record AudienceMembers(List<String> contactIds, Map<String, String> channelAccountByContactId, long total,
            long people, boolean truncated) {}

    Optional<SegmentRef> find(String workspaceId, String segmentId);

    /** @throws me.nageshk.omnichannel.common.api.ForbiddenException when the actor may not use segments */
    void requireAccess(String workspaceId, AuthenticatedUser actor);

    /** Resolves for the actor and records the campaign as the segment's last send; 403 without access. */
    AudienceMembers resolve(String workspaceId, String segmentId, AuthenticatedUser actor, String campaignId,
            String campaignName);
}
