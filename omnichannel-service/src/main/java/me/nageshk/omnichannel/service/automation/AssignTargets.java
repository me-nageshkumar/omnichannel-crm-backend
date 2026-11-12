package me.nageshk.omnichannel.service.automation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.db.automation.AssignMode;
import me.nageshk.omnichannel.db.tenant.TeamRepository;
import me.nageshk.omnichannel.db.tenant.WorkspaceRepository;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.security.user.UserAdminService;
import org.springframework.stereotype.Component;

/** Whether an assignment's agent or team can be used, shared by rules (publish) and chatbot nodes (validation). */
@Component
public class AssignTargets {

    private final TeamRepository teams;
    private final UserAdminService userAdmin;
    private final WorkspaceRepository workspaces;

    public AssignTargets(TeamRepository teams, UserAdminService userAdmin, WorkspaceRepository workspaces) {
        this.teams = teams;
        this.userAdmin = userAdmin;
        this.workspaces = workspaces;
    }

    /** Keys {@code mode}, {@code userId}, {@code teamId}; empty when usable. */
    public Map<String, String> check(String workspaceId, AssignMode mode, String userId, String teamId) {
        Map<String, String> out = new LinkedHashMap<>();
        if (mode == null) {
            out.put("mode", "Choose an agent or a team");
            return out;
        }
        switch (mode) {
            case AGENT -> {
                if (userId == null || userId.isBlank()) {
                    out.put("userId", "Choose an agent");
                } else {
                    String customerId = workspaces.findById(workspaceId).map(w -> w.getCustomerId()).orElse(null);
                    List<User> agents = customerId == null ? List.of() : userAdmin.withAccessTo(customerId, workspaceId);
                    if (agents.stream().noneMatch(u -> u.isActive() && u.getId().equals(userId))) {
                        out.put("userId", "The agent is no longer in the brand");
                    }
                }
            }
            case TEAM_QUEUE, TEAM_ROUND_ROBIN -> {
                if (teamId == null || teamId.isBlank()) {
                    out.put("teamId", "Choose a team");
                } else if (teams.findByIdAndWorkspaceId(teamId, workspaceId).isEmpty()) {
                    out.put("teamId", "The team no longer exists");
                }
            }
        }
        return out;
    }
}
