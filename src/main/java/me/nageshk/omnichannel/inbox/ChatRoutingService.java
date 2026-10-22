package me.nageshk.omnichannel.inbox;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import me.nageshk.omnichannel.phone.PhoneNumber;
import me.nageshk.omnichannel.phone.PhoneNumberRepository;
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

/**
 * Applies the owning team's effective routing setting to a new or reopened chat and records the {@code ROUTED}
 * action (design §3.4). A number without an owning team routes nothing.
 */
@Service
public class ChatRoutingService {

    private final InboxSettingService settings;
    private final MongoTemplate mongo;
    private final TeamRepository teams;
    private final PhoneNumberRepository phoneNumbers;
    private final UserRepository users;
    private final UserAdminService userAdmin;
    private final WaWebChatRepository chats;
    private final WaWebChatActionRepository actions;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    public ChatRoutingService(InboxSettingService settings, MongoTemplate mongo, TeamRepository teams,
            PhoneNumberRepository phoneNumbers, UserRepository users, UserAdminService userAdmin,
            WaWebChatRepository chats, WaWebChatActionRepository actions, ApplicationEventPublisher publisher,
            Clock clock) {
        this.settings = settings;
        this.mongo = mongo;
        this.teams = teams;
        this.phoneNumbers = phoneNumbers;
        this.users = users;
        this.userAdmin = userAdmin;
        this.chats = chats;
        this.actions = actions;
        this.publisher = publisher;
        this.clock = clock;
    }

    /**
     * Sets the assignment the owning team's setting asks for and saves the chat; a chat the setting leaves alone
     * (or whose number has no owning team) is returned untouched. Publishes {@link InboxEvents.ActionRecorded} but
     * not {@code ChatChanged}: the caller is the ingestion, which publishes the fresh chat afterwards.
     */
    public WaWebChat route(WaWebChat chat) {
        Team team = owningTeam(chat);
        if (team == null) return chat;
        InboxSetting setting = settings.effective(chat.getWorkspaceId(), team.getId());
        InboxSetting.Routing routing = setting.getRouting();
        if (routing == null || routing.mode() == RoutingMode.NONE) return chat;
        Map<String, User> reachable = reachable(chat);
        switch (routing.mode()) {
            case TEAM -> {
                return routed(chat, null, team);
            }
            case USER -> {
                User user = routing.userId() == null ? null : reachable.get(routing.userId());
                // the configured member can no longer take chats: the team queue
                return routed(chat, user != null && eligible(user, team, chat.getWorkspaceId()) ? user : null, team);
            }
            case ROUND_ROBIN -> {
                User next = nextMember(team, reachable, chat.getWorkspaceId());
                if (next != null) {
                    // only the cursor: saving the whole team could revert an edit made since it was read
                    mongo.updateFirst(new Query(Criteria.where("_id").is(team.getId())),
                            new Update().set("roundRobinCursor", next.getId()), Team.class);
                }
                return routed(chat, next, team);
            }
            default -> {
                return chat;
            }
        }
    }

    /** The team that works the chat's number, or null when the number has none (or it was deleted). */
    private Team owningTeam(WaWebChat chat) {
        String teamId = phoneNumbers.findById(chat.getPhoneNumberId()).map(PhoneNumber::getTeamId).orElse(null);
        return teamId == null ? null : teams.findByIdAndWorkspaceId(teamId, chat.getWorkspaceId()).orElse(null);
    }

    /** The first eligible member after the team's cursor in {@code memberIds} order, wrapping; null when none is. */
    private User nextMember(Team team, Map<String, User> reachable, String workspaceId) {
        List<String> members = team.getMemberIds();
        if (members.isEmpty()) return null;
        int start = members.indexOf(team.getRoundRobinCursor()) + 1;
        for (int i = 0; i < members.size(); i++) {
            User u = reachable.get(members.get((start + i) % members.size()));
            if (u != null && eligible(u, team, workspaceId)) return u;
        }
        return null;
    }

    /** The users with access to the chat's workspace, by id. */
    private Map<String, User> reachable(WaWebChat chat) {
        return userAdmin.withAccessTo(chat.getCustomerId(), chat.getWorkspaceId()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
    }

    /** Active, not disabled anywhere that matters, and a member of the team. */
    static boolean eligible(User u, Team team, String workspaceId) {
        return u.isActive() && u.getDisabled() == null && UserAdminService.brandDisabled(u, workspaceId) == null
                && team.getMemberIds().contains(u.getId());
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
