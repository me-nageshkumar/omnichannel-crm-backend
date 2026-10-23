package me.nageshk.omnichannel.tenant;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import java.util.Set;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import me.nageshk.omnichannel.api.DuplicateException;
import me.nageshk.omnichannel.api.NotFoundException;
import me.nageshk.omnichannel.api.NotInWorkspaceException;
import me.nageshk.omnichannel.api.ValidationException;
import me.nageshk.omnichannel.phone.PhoneNumber;
import me.nageshk.omnichannel.phone.PhoneNumberRepository;
import me.nageshk.omnichannel.user.User;
import me.nageshk.omnichannel.user.UserAdminService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Teams of a workspace: list with members, create, and rename with a new member set. Every member
 * must have access to the workspace (a role there, or a COMPANY role). Names are unique within the
 * workspace. Authorities are checked by the controller.
 */
@Service
public class TeamService {

    /** A team with its members resolved, in the stored order. */
    public record TeamWithMembers(Team team, List<User> members, List<PhoneNumber> phoneNumbers) {}

    private final TeamRepository teams;
    private final WorkspaceRepository workspaces;
    private final UserAdminService userAdmin;
    private final PhoneNumberRepository phoneNumbers;
    private final ApplicationEventPublisher events;

    public TeamService(TeamRepository teams, WorkspaceRepository workspaces, UserAdminService userAdmin,
            PhoneNumberRepository phoneNumbers, ApplicationEventPublisher events) {
        this.teams = teams;
        this.workspaces = workspaces;
        this.userAdmin = userAdmin;
        this.phoneNumbers = phoneNumbers;
        this.events = events;
    }

    /** @throws NotFoundException if the workspace is not the customer's */
    public List<TeamWithMembers> list(String customerId, String workspaceId) {
        requireWorkspace(customerId, workspaceId);
        Map<String, User> byId = usersWithAccess(customerId, workspaceId);
        List<Team> all = teams.findByWorkspaceIdOrderByName(workspaceId);
        Map<String, List<PhoneNumber>> numbersByTeam = phoneNumbers
                .findByWorkspaceIdAndTeamIdIn(workspaceId, all.stream().map(Team::getId).toList()).stream()
                .collect(Collectors.groupingBy(PhoneNumber::getTeamId));
        return all.stream()
                .map(t -> resolve(t, byId, numbersByTeam.getOrDefault(t.getId(), List.of())))
                .toList();
    }

    /**
     * @throws NotFoundException if the workspace is not the customer's
     * @throws ValidationException if the name is blank
     * @throws DuplicateException (field {@code name}) if the workspace has a team so named
     */
    public TeamWithMembers create(String customerId, String workspaceId, String name) {
        requireWorkspace(customerId, workspaceId);
        Team t = new Team();
        t.setCustomerId(customerId);
        t.setWorkspaceId(workspaceId);
        t.setName(requireName(name));
        return resolve(save(t), usersWithAccess(customerId, workspaceId));
    }

    /**
     * Renames the team and replaces its members (duplicates dropped, order kept).
     *
     * @throws NotFoundException if the workspace is not the customer's or the team is not the workspace's
     * @throws NotInWorkspaceException if a member is not a user with access to the workspace
     * @throws DuplicateException (field {@code name}) if another team of the workspace has the name
     */
    public TeamWithMembers update(String customerId, String workspaceId, String teamId, String name,
            List<String> memberIds) {
        requireWorkspace(customerId, workspaceId);
        String newName = requireName(name);
        Team t = teams.findByIdAndWorkspaceId(teamId, workspaceId)
                .orElseThrow(() -> new NotFoundException("team not found"));
        Map<String, User> byId = usersWithAccess(customerId, workspaceId);
        List<String> members = memberIds == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(memberIds));
        if (members.stream().anyMatch(id -> id == null || !byId.containsKey(id))) {
            throw new NotInWorkspaceException("every member must be a user with access to this workspace");
        }
        Set<String> removed = new LinkedHashSet<>(t.getMemberIds());
        members.forEach(removed::remove);
        t.setName(newName);
        t.setMemberIds(new ArrayList<>(members));
        Team saved = save(t);
        if (!removed.isEmpty()) events.publishEvent(new TeamEvents.MembersRemoved(saved, Set.copyOf(removed)));
        return resolve(saved, byId);
    }

    private void requireWorkspace(String customerId, String workspaceId) {
        if (customerId == null || workspaces.findByIdAndCustomerId(workspaceId, customerId).isEmpty()) {
            throw new NotFoundException("workspace not found");
        }
    }

    private Map<String, User> usersWithAccess(String customerId, String workspaceId) {
        return userAdmin.withAccessTo(customerId, workspaceId).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
    }

    private TeamWithMembers resolve(Team t, Map<String, User> byId) {
        return resolve(t, byId, phoneNumbers.findByWorkspaceIdAndTeamIdIn(t.getWorkspaceId(), List.of(t.getId())));
    }

    private static TeamWithMembers resolve(Team t, Map<String, User> byId, List<PhoneNumber> numbers) {
        return new TeamWithMembers(t, t.getMemberIds().stream().map(byId::get).filter(u -> u != null).toList(),
                numbers);
    }

    private Team save(Team t) {
        try {
            return teams.save(t);
        } catch (DuplicateKeyException e) {
            throw new DuplicateException("name", "a team with this name already exists in this workspace");
        }
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) throw new ValidationException("name", "must not be blank");
        return name.trim();
    }
}
