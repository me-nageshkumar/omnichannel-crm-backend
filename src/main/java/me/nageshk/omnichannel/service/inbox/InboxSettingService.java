package me.nageshk.omnichannel.service.inbox;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.db.inbox.InboxSetting;
import me.nageshk.omnichannel.db.inbox.InboxSettingRepository;
import me.nageshk.omnichannel.db.inbox.RoutingMode;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.phone.PhoneNumber;
import me.nageshk.omnichannel.db.phone.PhoneNumberRepository;
import me.nageshk.omnichannel.db.tenant.Team;
import me.nageshk.omnichannel.db.tenant.TeamRepository;
import me.nageshk.omnichannel.db.tenant.Workspace;
import me.nageshk.omnichannel.db.tenant.WorkspaceRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/** Brand default and team overrides of the routing setting, and the effective one for a chat (design §3.4). */
@Service
public class InboxSettingService {

    private final InboxSettingRepository settings;
    private final TeamRepository teams;
    private final WorkspaceRepository workspaces;
    private final PhoneNumberRepository phoneNumbers;

    public InboxSettingService(InboxSettingRepository settings, TeamRepository teams, WorkspaceRepository workspaces,
            PhoneNumberRepository phoneNumbers) {
        this.settings = settings;
        this.teams = teams;
        this.workspaces = workspaces;
        this.phoneNumbers = phoneNumbers;
    }

    /** The brand default, created with the spec's values the first time it is asked for. */
    public InboxSetting brandDefault(String workspaceId) {
        return settings.findByWorkspaceIdAndTeamId(workspaceId, null).orElseGet(() -> {
            Workspace w = workspaces.findById(workspaceId)
                    .orElseThrow(() -> new NotFoundException("workspace not found"));
            InboxSetting s = new InboxSetting();
            s.setCustomerId(w.getCustomerId());
            s.setWorkspaceId(workspaceId);
            try {
                return settings.save(s);
            } catch (DuplicateKeyException raced) {
                return settings.findByWorkspaceIdAndTeamId(workspaceId, null).orElseThrow();
            }
        });
    }

    public Optional<InboxSetting> override(String workspaceId, String teamId) {
        return teamId == null ? Optional.empty() : settings.findByWorkspaceIdAndTeamId(workspaceId, teamId);
    }

    /** The team's override when it has one, else the brand default; {@code teamId} null means the default. */
    public InboxSetting effective(String workspaceId, String teamId) {
        return override(workspaceId, teamId).orElseGet(() -> brandDefault(workspaceId));
    }

    /** The setting that governs a chat: its number's owning team's, else the brand default. */
    public InboxSetting effectiveFor(WaWebChat chat) {
        String teamId = phoneNumbers.findById(chat.getPhoneNumberId()).map(PhoneNumber::getTeamId).orElse(null);
        return effective(chat.getWorkspaceId(), teamId);
    }

    public InboxSetting putDefault(String workspaceId, RoutingSetting.Request request) {
        InboxSetting current = brandDefault(workspaceId);
        apply(current, request, null);
        return settings.save(current);
    }

    /** Creates the team's override from the brand default (then applies the request) or updates the existing one. */
    public InboxSetting putOverride(String workspaceId, String teamId, RoutingSetting.Request request) {
        Team team = requireTeam(workspaceId, teamId);
        InboxSetting target = override(workspaceId, teamId).orElseGet(() -> brandDefault(workspaceId).copyFor(teamId));
        apply(target, request, team);
        return settings.save(target);
    }

    public void deleteOverride(String workspaceId, String teamId) {
        requireTeam(workspaceId, teamId);
        settings.deleteByWorkspaceIdAndTeamId(workspaceId, teamId);
    }

    public RoutingSetting defaultView(String workspaceId) {
        return RoutingSetting.of(brandDefault(workspaceId), null, false);
    }

    /** The team's override, or the brand default flagged {@code inherited}. */
    public RoutingSetting teamView(String workspaceId, String teamId) {
        requireTeam(workspaceId, teamId);
        return override(workspaceId, teamId).map(s -> RoutingSetting.of(s, teamId, false))
                .orElseGet(() -> RoutingSetting.of(brandDefault(workspaceId), teamId, true));
    }

    /** One entry per team of the workspace, in team-name order. */
    public List<RoutingSetting> teamSettings(String workspaceId) {
        InboxSetting fallback = brandDefault(workspaceId);
        Map<String, InboxSetting> overrides = settings.findByWorkspaceId(workspaceId).stream()
                .filter(s -> s.getTeamId() != null)
                .collect(Collectors.toMap(InboxSetting::getTeamId, Function.identity()));
        return teams.findByWorkspaceIdOrderByName(workspaceId).stream()
                .map(t -> overrides.containsKey(t.getId()) ? RoutingSetting.of(overrides.get(t.getId()), t.getId(), false)
                        : RoutingSetting.of(fallback, t.getId(), true))
                .toList();
    }

    private Team requireTeam(String workspaceId, String teamId) {
        if (teamId == null || teamId.isBlank()) throw new NotFoundException("team not found");
        return teams.findByIdAndWorkspaceId(teamId, workspaceId).orElseThrow(() -> new NotFoundException("team not found"));
    }

    /** Folds the request into {@code target}; {@code team} is null for the brand default. */
    private static void apply(InboxSetting target, RoutingSetting.Request request, Team team) {
        RoutingSetting.Request r = request == null ? new RoutingSetting.Request(null, null, null, null, null, null) : request;
        RoutingSetting current = RoutingSetting.of(target, target.getTeamId(), false);
        boolean assignToTeam = r.assignToTeam() != null ? r.assignToTeam() : current.assignToTeam();
        boolean roundRobin = r.roundRobin() != null ? r.roundRobin() : current.roundRobin();
        // a request that turns round robin on, or assignment off, means the inherited member goes too
        boolean dropsMember = Boolean.TRUE.equals(r.roundRobin()) || Boolean.FALSE.equals(r.assignToTeam());
        String userId = r.userId() != null ? (r.userId().isBlank() ? null : r.userId())
                : dropsMember ? null : current.userId();
        int stickyDays = r.stickyDays() != null ? r.stickyDays() : current.stickyDays();

        if (stickyDays < 0 || stickyDays > InboxSetting.MAX_STICKY_DAYS) {
            throw new ValidationException("stickyDays", "must be between 0 and " + InboxSetting.MAX_STICKY_DAYS);
        }
        RoutingMode mode;
        if (!assignToTeam) {
            mode = RoutingMode.NONE;
            userId = null;
        } else if (roundRobin && userId != null) {
            throw new ValidationException("routing", "round robin and one member cannot both be set");
        } else if (roundRobin) {
            mode = RoutingMode.ROUND_ROBIN;
        } else if (userId != null) {
            if (team == null) throw new ValidationException("userId", "only a team override may name a member");
            if (!team.getMemberIds().contains(userId)) {
                throw new ValidationException("userId", "must be a member of the team");
            }
            mode = RoutingMode.USER;
        } else {
            mode = RoutingMode.TEAM;
        }
        target.setRouting(new InboxSetting.Routing(mode, mode == RoutingMode.USER ? userId : null));
        target.setStickyDays(stickyDays);
        if (r.claimOnReply() != null) target.setClaimOnReply(r.claimOnReply());
        if (r.keepAssignmentOnClose() != null) target.setKeepAssignmentOnClose(r.keepAssignmentOnClose());
    }
}
