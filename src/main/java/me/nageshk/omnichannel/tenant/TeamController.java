package me.nageshk.omnichannel.tenant;

import java.util.List;
import me.nageshk.omnichannel.auth.AuthenticatedUser;
import me.nageshk.omnichannel.tenant.TeamService.TeamWithMembers;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Teams of one workspace: {@code user:view} to list, {@code team:manage} to create, rename and set
 * members. A workspace the caller cannot reach, or a team of another workspace, answers 404.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/teams")
public class TeamController {

    public record Member(String id, String name, String username) {}

    public record TeamView(String id, String name, List<Member> members) {}

    public record CreateRequest(String name) {}

    public record UpdateRequest(String name, List<String> memberIds) {}

    private final TeamService service;

    public TeamController(TeamService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("@access.has(#workspaceId, 'user:view')")
    public List<TeamView> list(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId) {
        return service.list(me.customerId(), workspaceId).stream().map(TeamController::view).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.has(#workspaceId, 'team:manage')")
    public TeamView create(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @RequestBody CreateRequest req) {
        return view(service.create(me.customerId(), workspaceId, req.name()));
    }

    @PutMapping("/{teamId}")
    @PreAuthorize("@access.has(#workspaceId, 'team:manage')")
    public TeamView update(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String teamId, @RequestBody UpdateRequest req) {
        return view(service.update(me.customerId(), workspaceId, teamId, req.name(), req.memberIds()));
    }

    private static TeamView view(TeamWithMembers t) {
        return new TeamView(t.team().getId(), t.team().getName(),
                t.members().stream().map(u -> new Member(u.getId(), u.getName(), u.getUsername())).toList());
    }
}
