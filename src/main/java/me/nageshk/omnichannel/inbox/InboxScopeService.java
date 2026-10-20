package me.nageshk.omnichannel.inbox;

import java.util.Set;
import java.util.stream.Collectors;
import me.nageshk.omnichannel.access.AccessService;
import me.nageshk.omnichannel.access.Authority;
import me.nageshk.omnichannel.auth.AuthenticatedUser;
import me.nageshk.omnichannel.inbox.InboxScope.Level;
import me.nageshk.omnichannel.inbox.InboxScope.ListScope;
import me.nageshk.omnichannel.tenant.Team;
import me.nageshk.omnichannel.tenant.TeamRepository;
import org.springframework.stereotype.Service;

/** Resolves a user's {@link InboxScope} in a workspace from its authorities and team memberships. */
@Service
public class InboxScopeService {

    private final AccessService access;
    private final TeamRepository teams;

    public InboxScopeService(AccessService access, TeamRepository teams) {
        this.access = access;
        this.teams = teams;
    }

    /**
     * @throws me.nageshk.omnichannel.api.NotFoundException if the user has no access to the workspace
     */
    public InboxScope scopeOf(AuthenticatedUser me, String workspaceId) {
        Set<String> authorities = access.inWorkspace(me, workspaceId);
        if (authorities.contains(Authority.INBOX_VIEW_ALL.code())) {
            return new InboxScope(Level.ALL, me.userId(), teamIds(workspaceId, null));
        }
        if (authorities.contains(Authority.INBOX_VIEW_TEAM.code())) {
            return new InboxScope(Level.TEAM, me.userId(), teamIds(workspaceId, me.userId()));
        }
        return new InboxScope(Level.MINE, me.userId(), Set.of());
    }

    /** The widest list the scope allows. */
    public ListScope defaultScope(InboxScope scope) {
        return switch (scope.level()) {
            case ALL -> ListScope.ALL;
            case TEAM -> ListScope.TEAM;
            case MINE -> ListScope.MINE;
        };
    }

    /** Ids of the workspace's teams, only those with {@code memberId} as a member when it is non-null. */
    private Set<String> teamIds(String workspaceId, String memberId) {
        return teams.findByWorkspaceIdOrderByName(workspaceId).stream()
                .filter(t -> memberId == null || t.getMemberIds().contains(memberId))
                .map(Team::getId)
                .collect(Collectors.toUnmodifiableSet());
    }
}
