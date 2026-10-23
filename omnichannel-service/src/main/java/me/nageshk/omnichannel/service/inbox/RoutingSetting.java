package me.nageshk.omnichannel.service.inbox;

import me.nageshk.omnichannel.db.inbox.InboxSetting;
import me.nageshk.omnichannel.db.inbox.RoutingMode;

/**
 * A routing setting as the console sees it: the stored mode unfolded into the two switches of design §3.4 plus the
 * optional member of the advanced "always assign to one member" option. {@code inherited} is true when a team has no
 * override and the brand default is shown in its place.
 */
public record RoutingSetting(
        String teamId,
        boolean inherited,
        boolean assignToTeam,
        boolean roundRobin,
        String userId,
        int stickyDays,
        boolean claimOnReply,
        boolean keepAssignmentOnClose) {

    /** What the console sends; a null field keeps the value the effective setting already has. */
    public record Request(Boolean assignToTeam, Boolean roundRobin, String userId, Integer stickyDays,
            Boolean claimOnReply, Boolean keepAssignmentOnClose) {
    }

    static RoutingSetting of(InboxSetting s, String teamId, boolean inherited) {
        RoutingMode mode = s.getRouting().mode();
        return new RoutingSetting(teamId, inherited, mode != RoutingMode.NONE, mode == RoutingMode.ROUND_ROBIN,
                mode == RoutingMode.USER ? s.getRouting().userId() : null, s.getStickyDays(), s.isClaimOnReply(),
                s.isKeepAssignmentOnClose());
    }
}
