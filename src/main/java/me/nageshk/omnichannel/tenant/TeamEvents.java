package me.nageshk.omnichannel.tenant;

import java.util.Set;

/** Changes to a team that other features react to. */
public final class TeamEvents {
    private TeamEvents() {
    }

    /** Members were taken off the team; published after the team is saved. */
    public record MembersRemoved(Team team, Set<String> removedUserIds) {
    }
}
