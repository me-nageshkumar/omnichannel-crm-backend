package me.nageshk.omnichannel.inbox;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import me.nageshk.omnichannel.tenant.Team;
import me.nageshk.omnichannel.tenant.TeamRepository;
import me.nageshk.omnichannel.user.User;
import me.nageshk.omnichannel.user.UserAdminService;
import me.nageshk.omnichannel.user.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/** Applies a number's routing setting to a new or reopened chat and records the {@code ROUTED} action. */
@Service
public class ChatRoutingService {

    private final InboxSettingService settings;
    private final InboxSettingRepository settingRepo;
    private final TeamRepository teams;
    private final UserRepository users;
    private final UserAdminService userAdmin;
    private final WaWebChatRepository chats;
    private final WaWebChatActionRepository actions;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    public ChatRoutingService(InboxSettingService settings, InboxSettingRepository settingRepo, TeamRepository teams,
            UserRepository users, UserAdminService userAdmin, WaWebChatRepository chats,
            WaWebChatActionRepository actions, ApplicationEventPublisher publisher, Clock clock) {
        this.settings = settings;
        this.settingRepo = settingRepo;
        this.teams = teams;
        this.users = users;
        this.userAdmin = userAdmin;
        this.chats = chats;
        this.actions = actions;
        this.publisher = publisher;
        this.clock = clock;
    }

    /**
     * Sets the assignment the number's settings ask for and saves the chat; a chat the settings leave alone is
     * returned untouched. Publishes {@link InboxEvents.ActionRecorded} but not {@code ChatChanged}: the caller is
     * the ingestion, which publishes the fresh chat afterwards.
     */
    public WaWebChat route(WaWebChat chat) {
        InboxSetting setting = settings.get(chat.getWorkspaceId(), chat.getPhoneNumberId());
        InboxSetting.Routing routing = setting.getRouting();
        if (routing == null || routing.mode() == RoutingMode.NONE) return chat;
        switch (routing.mode()) {
            case TEAM -> {
                Team team = team(chat, routing.teamId());
                if (team == null) return chat;
                return routed(chat, null, team);
            }
            case USER -> {
                User user = routing.userId() == null ? null : users.findById(routing.userId()).orElse(null);
                if (user == null) return chat;
                return routed(chat, user, null);
            }
            case ROUND_ROBIN -> {
                Team team = team(chat, routing.teamId());
                if (team == null) return chat;
                User next = nextMember(chat, setting, team);
                if (next != null) {
                    setting.setCustomerId(chat.getCustomerId());
                    setting.setRoundRobinCursor(next.getId());
                    settingRepo.save(setting);
                }
                return routed(chat, next, team);
            }
            default -> {
                return chat;
            }
        }
    }

    private Team team(WaWebChat chat, String teamId) {
        return teamId == null ? null : teams.findByIdAndWorkspaceId(teamId, chat.getWorkspaceId()).orElse(null);
    }

    /** The first eligible member after the cursor in {@code memberIds} order, wrapping; null when none is. */
    private User nextMember(WaWebChat chat, InboxSetting setting, Team team) {
        List<String> members = team.getMemberIds();
        if (members.isEmpty()) return null;
        Map<String, User> reachable = userAdmin.withAccessTo(chat.getCustomerId(), chat.getWorkspaceId()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        int start = members.indexOf(setting.getRoundRobinCursor()) + 1;
        for (int i = 0; i < members.size(); i++) {
            User u = reachable.get(members.get((start + i) % members.size()));
            if (u != null && eligible(u, chat.getWorkspaceId())) return u;
        }
        return null;
    }

    private static boolean eligible(User u, String workspaceId) {
        return u.isActive() && u.getDisabled() == null && UserAdminService.brandDisabled(u, workspaceId) == null;
    }

    private WaWebChat routed(WaWebChat chat, User user, Team team) {
        chat.setAssignedUserId(user == null ? null : user.getId());
        chat.setAssignedTeamId(team == null ? null : team.getId());
        chat.setAssignedAt(clock.instant());
        chat = chats.save(chat);
        WaWebChatAction a = new WaWebChatAction();
        a.setCustomerId(chat.getCustomerId());
        a.setWorkspaceId(chat.getWorkspaceId());
        a.setChatId(chat.getId());
        a.setType(ActionType.ROUTED);
        if (user != null) {
            a.setToUserId(user.getId());
            a.setToUserName(user.getName());
        }
        if (team != null) {
            a.setToTeamId(team.getId());
            a.setToTeamName(team.getName());
        }
        a.setAt(chat.getAssignedAt());
        a = actions.save(a);
        publisher.publishEvent(new InboxEvents.ActionRecorded(chat, a));
        return chat;
    }
}
