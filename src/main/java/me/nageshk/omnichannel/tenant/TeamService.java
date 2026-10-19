package me.nageshk.omnichannel.tenant;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import me.nageshk.omnichannel.api.DuplicateException;
import me.nageshk.omnichannel.api.NotFoundException;
import me.nageshk.omnichannel.api.NotInWorkspaceException;
import me.nageshk.omnichannel.api.ValidationException;
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
    public record TeamWithMembers(Team team, List<User> members) {}

    private final TeamRepository teams;
    private final WorkspaceRepository workspaces;
    private final UserAdminService userAdmin;

    public TeamService(TeamRepository teams, WorkspaceRepository workspaces, UserAdminService userAdmin) {
        this.teams = teams;
        this.workspaces = workspaces;
        this.userAdmin = userAdmin;
    }

    /** @throws NotFoundException if the workspace is not the customer's */
    public List<TeamWithMembers> list(String customerId, String workspaceId) {
        requireWorkspace(customerId, workspaceId);
        Map<String, User> byId = usersWithAccess(customerId, workspaceId);
        return teams.findByWorkspaceIdOrderByName(workspaceId).stream()
                .map(t -> resolve(t, byId))
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
        t.setName(newName);
        t.setMemberIds(new ArrayList<>(members));
        return resolve(save(t), byId);
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

    private static TeamWithMembers resolve(Team t, Map<String, User> byId) {
        return new TeamWithMembers(t, t.getMemberIds().stream().map(byId::get).filter(u -> u != null).toList());
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
