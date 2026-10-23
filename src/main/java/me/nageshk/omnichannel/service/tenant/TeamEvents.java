package me.nageshk.omnichannel.service.tenant;

import java.util.Set;
import me.nageshk.omnichannel.db.tenant.Team;

/** Changes to a team that other features react to. */
public final class TeamEvents {
    private TeamEvents() {
    }

    /** Members were taken off the team; published after the team is saved. */
    public record MembersRemoved(Team team, Set<String> removedUserIds) {
    }
}
