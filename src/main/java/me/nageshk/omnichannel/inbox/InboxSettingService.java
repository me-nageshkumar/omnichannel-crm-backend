package me.nageshk.omnichannel.inbox;

import me.nageshk.omnichannel.api.NotFoundException;
import me.nageshk.omnichannel.api.ValidationException;
import me.nageshk.omnichannel.tenant.TeamRepository;
import me.nageshk.omnichannel.tenant.WorkspaceRepository;
import me.nageshk.omnichannel.user.User;
import me.nageshk.omnichannel.user.UserAdminService;
import org.springframework.stereotype.Service;

/** Reads and validates the routing settings of a phone number. */
@Service
public class InboxSettingService {

    /** The PUT body; a null flag keeps the stored value. */
    public record SettingsRequest(InboxSetting.Routing routing, Boolean claimOnReply,
            Boolean keepAssignmentOnClose) {
    }

    private final InboxSettingRepository settings;
    private final TeamRepository teams;
    private final WorkspaceRepository workspaces;
    private final UserAdminService userAdmin;

    public InboxSettingService(InboxSettingRepository settings, TeamRepository teams,
            WorkspaceRepository workspaces, UserAdminService userAdmin) {
        this.settings = settings;
        this.teams = teams;
        this.workspaces = workspaces;
        this.userAdmin = userAdmin;
    }

    /** The stored settings, or an unsaved default (mode NONE, claim on reply, assignment not kept on close). */
    public InboxSetting get(String workspaceId, String phoneNumberId) {
        return settings.findByPhoneNumberId(phoneNumberId).orElseGet(() -> {
            InboxSetting s = new InboxSetting();
            s.setWorkspaceId(workspaceId);
            s.setPhoneNumberId(phoneNumberId);
            return s;
        });
    }

    /** Replaces the routing; the fields the mode does not use are dropped. */
    public InboxSetting put(String workspaceId, String phoneNumberId, SettingsRequest request) {
        String customerId = workspaces.findById(workspaceId)
                .orElseThrow(() -> new NotFoundException("workspace not found")).getCustomerId();
        InboxSetting.Routing wanted = request.routing();
        if (wanted == null || wanted.mode() == null) throw new ValidationException("mode", "required");
        InboxSetting.Routing routing = switch (wanted.mode()) {
            case NONE -> new InboxSetting.Routing(RoutingMode.NONE, null, null);
            case TEAM, ROUND_ROBIN -> new InboxSetting.Routing(wanted.mode(), checkedTeam(workspaceId,
                    wanted.teamId()), null);
            case USER -> new InboxSetting.Routing(RoutingMode.USER, null,
                    checkedUser(customerId, workspaceId, wanted.userId()));
        };
        InboxSetting s = get(workspaceId, phoneNumberId);
        s.setCustomerId(customerId);
        s.setRouting(routing);
        if (request.claimOnReply() != null) s.setClaimOnReply(request.claimOnReply());
        if (request.keepAssignmentOnClose() != null) s.setKeepAssignmentOnClose(request.keepAssignmentOnClose());
        return settings.save(s);
    }

    private String checkedTeam(String workspaceId, String teamId) {
        if (teamId == null || teamId.isBlank()) throw new ValidationException("teamId", "required");
        if (teams.findByIdAndWorkspaceId(teamId, workspaceId).isEmpty())
            throw new ValidationException("teamId", "unknown team");
        return teamId;
    }

    private String checkedUser(String customerId, String workspaceId, String userId) {
        if (userId == null || userId.isBlank()) throw new ValidationException("userId", "required");
        if (userAdmin.withAccessTo(customerId, workspaceId).stream().map(User::getId).noneMatch(userId::equals))
            throw new ValidationException("userId", "unknown user");
        return userId;
    }
}
