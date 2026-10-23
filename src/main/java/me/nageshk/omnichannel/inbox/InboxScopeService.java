package me.nageshk.omnichannel.inbox;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import me.nageshk.omnichannel.access.AccessService;
import me.nageshk.omnichannel.access.Authority;
import me.nageshk.omnichannel.api.NotFoundException;
import me.nageshk.omnichannel.auth.AuthenticatedUser;
import me.nageshk.omnichannel.inbox.InboxScope.Level;
import me.nageshk.omnichannel.inbox.InboxScope.ListScope;
import me.nageshk.omnichannel.phone.PhoneNumber;
import me.nageshk.omnichannel.phone.PhoneNumberRepository;
import me.nageshk.omnichannel.tenant.Team;
import me.nageshk.omnichannel.tenant.TeamRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Resolves a user's {@link InboxScope} in a workspace from its authorities, team memberships and the numbers those
 * teams own, and which numbers the user may open at all (design §3.1, §3.5).
 */
@Service
public class InboxScopeService {

    /** A number the user may open: one of their teams' own, or a guest number holding a chat they can see. */
    public record VisibleNumber(PhoneNumber number, boolean guest) {
    }

    private final AccessService access;
    private final TeamRepository teams;
    private final PhoneNumberRepository phoneNumbers;
    private final MongoTemplate mongo;

    public InboxScopeService(AccessService access, TeamRepository teams, PhoneNumberRepository phoneNumbers,
            MongoTemplate mongo) {
        this.access = access;
        this.teams = teams;
        this.phoneNumbers = phoneNumbers;
        this.mongo = mongo;
    }

    /**
     * The widest scope the user's authorities in the workspace grant. Requires {@code inbox:view}, so callers are
     * safe even without a {@code @PreAuthorize} check.
     *
     * @throws NotFoundException if the user has no access to the workspace or lacks {@code inbox:view} there (also
     *     for a disabled workspace, which grants no authorities)
     */
    public InboxScope scopeOf(AuthenticatedUser me, String workspaceId) {
        Set<String> authorities = access.inWorkspace(me, workspaceId);
        if (!authorities.contains(Authority.INBOX_VIEW.code())) throw new NotFoundException("workspace not found");
        if (authorities.contains(Authority.INBOX_VIEW_ALL.code())) {
            Set<String> every = phoneNumbers.findByWorkspaceIdOrderByNumber(workspaceId).stream()
                    .map(PhoneNumber::getId).collect(Collectors.toUnmodifiableSet());
            return new InboxScope(Level.ALL, me.userId(), teamIds(workspaceId, null), every);
        }
        Level level = authorities.contains(Authority.INBOX_VIEW_TEAM.code()) ? Level.TEAM
                : authorities.contains(Authority.INBOX_VIEW_QUEUE.code()) ? Level.QUEUE : Level.MINE;
        Set<String> own = teamIds(workspaceId, me.userId());
        Set<String> owned = own.isEmpty() ? Set.of()
                : phoneNumbers.findByWorkspaceIdAndTeamIdIn(workspaceId, own).stream().map(PhoneNumber::getId)
                        .collect(Collectors.toUnmodifiableSet());
        return new InboxScope(level, me.userId(), own, owned);
    }

    /** The widest list the scope allows; QUEUE opens on Mine, the queue being a tab the agent chooses. */
    public ListScope defaultScope(InboxScope scope) {
        return switch (scope.level()) {
            case ALL -> ListScope.ALL;
            case TEAM -> ListScope.TEAM;
            case QUEUE, MINE -> ListScope.MINE;
        };
    }

    /**
     * The numbers the user may open, by number: every number of the workspace at ALL; otherwise the numbers their
     * teams own plus, flagged {@code guest}, any other number holding a chat their scope covers.
     */
    public List<VisibleNumber> visibleNumbers(AuthenticatedUser me, String workspaceId) {
        return visibleNumbers(scopeOf(me, workspaceId), workspaceId);
    }

    public List<VisibleNumber> visibleNumbers(InboxScope scope, String workspaceId) {
        List<PhoneNumber> all = phoneNumbers.findByWorkspaceIdOrderByNumber(workspaceId);
        if (scope.level() == Level.ALL) return all.stream().map(n -> new VisibleNumber(n, false)).toList();
        Set<String> guests = new java.util.HashSet<>(mongo.findDistinct(
                new Query(new Criteria().andOperator(where("workspaceId").is(workspaceId), scope.criteria())),
                "phoneNumberId", WaWebChat.class, String.class));
        guests.removeAll(scope.ownedNumberIds());
        List<VisibleNumber> visible = new ArrayList<>();
        for (PhoneNumber n : all) {
            if (scope.ownedNumberIds().contains(n.getId())) visible.add(new VisibleNumber(n, false));
            else if (guests.contains(n.getId())) visible.add(new VisibleNumber(n, true));
        }
        visible.sort(Comparator.comparing(v -> v.number().getNumber()));
        return visible;
    }

    /** Ids of the workspace's teams, only those with {@code memberId} as a member when it is non-null. */
    private Set<String> teamIds(String workspaceId, String memberId) {
        return teams.findByWorkspaceIdOrderByName(workspaceId).stream()
                .filter(t -> memberId == null || t.getMemberIds().contains(memberId))
                .map(Team::getId)
                .collect(Collectors.toUnmodifiableSet());
    }
}
