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
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/** Applies a number's routing setting to a new or reopened chat and records the {@code ROUTED} action. */
@Service
public class ChatRoutingService {

    private final InboxSettingService settings;
    private final MongoTemplate mongo;
    private final TeamRepository teams;
    private final UserRepository users;
    private final UserAdminService userAdmin;
    private final WaWebChatRepository chats;
    private final WaWebChatActionRepository actions;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    public ChatRoutingService(InboxSettingService settings, MongoTemplate mongo, TeamRepository teams,
            UserRepository users, UserAdminService userAdmin, WaWebChatRepository chats,
            WaWebChatActionRepository actions, ApplicationEventPublisher publisher, Clock clock) {
        this.settings = settings;
        this.mongo = mongo;
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
                if (reachable(chat).get(user.getId()) != null && eligible(user, chat.getWorkspaceId())) {
                    return routed(chat, user, null);
                }
                // the configured user can no longer take chats: the team queue if one is set, else nobody
                Team team = team(chat, routing.teamId());
                return team == null ? chat : routed(chat, null, team);
            }
            case ROUND_ROBIN -> {
                Team team = team(chat, routing.teamId());
                if (team == null) return chat;
                User next = nextMember(chat, setting, team);
                if (next != null) {
                    // only the cursor: saving the whole setting could revert an edit made since it was read
                    mongo.updateFirst(new Query(Criteria.where("phoneNumberId").is(chat.getPhoneNumberId())),
                            new Update().set("roundRobinCursor", next.getId()), InboxSetting.class);
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
        Map<String, User> reachable = reachable(chat);
        int start = members.indexOf(setting.getRoundRobinCursor()) + 1;
        for (int i = 0; i < members.size(); i++) {
            User u = reachable.get(members.get((start + i) % members.size()));
            if (u != null && eligible(u, chat.getWorkspaceId())) return u;
        }
        return null;
    }

    /** The users with access to the chat's workspace, by id. */
    private Map<String, User> reachable(WaWebChat chat) {
        return userAdmin.withAccessTo(chat.getCustomerId(), chat.getWorkspaceId()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
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
