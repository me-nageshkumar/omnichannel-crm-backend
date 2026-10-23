package me.nageshk.omnichannel.inbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import me.nageshk.omnichannel.phone.PhoneNumber;
import me.nageshk.omnichannel.phone.PhoneNumberRepository;
import me.nageshk.omnichannel.tenant.Team;
import me.nageshk.omnichannel.tenant.TeamRepository;
import me.nageshk.omnichannel.user.Availability;
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
 * Applies a team's effective routing setting to a chat and records the {@code ROUTED} action (design §3.2–3.4):
 * a returning customer first (sticky), then the setting's mode over the team's available members. A number without
 * an owning team routes nothing.
 */
@Service
public class ChatRoutingService {

    /**
     * How one routing run differs from the default: a member to skip (a hand-back), whether the sticky rule may
     * apply, and the reason to record on the action.
     */
    public record RoutingOptions(String excludeUserId, boolean sticky, RoutedReason reason) {
        public static final RoutingOptions DEFAULT = new RoutingOptions(null, true, null);
    }

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
     * Routes under the owning team of the chat's number with {@link RoutingOptions#DEFAULT}; a chat whose number
     * has no owning team is returned untouched. Publishes {@link InboxEvents.ActionRecorded} but not
     * {@code ChatChanged}: the caller is the ingestion, which publishes the fresh chat afterwards.
     */
    public WaWebChat route(WaWebChat chat) {
        Team team = owningTeam(chat);
        return team == null ? chat : route(chat, team, RoutingOptions.DEFAULT);
    }

    /** Routes under {@code team}'s effective setting; the chat is saved only when the setting assigns something. */
    public WaWebChat route(WaWebChat chat, Team team, RoutingOptions options) {
        InboxSetting setting = settings.effective(chat.getWorkspaceId(), team.getId());
        InboxSetting.Routing routing = setting.getRouting();
        if (routing == null || routing.mode() == RoutingMode.NONE) return chat;
        Map<String, User> reachable = reachable(chat);
        User sticky = options.sticky() ? stickyAgent(chat, setting, team, reachable, options.excludeUserId()) : null;
        if (sticky != null) return routed(chat, sticky, team, RoutedReason.STICKY, options.excludeUserId());
        switch (routing.mode()) {
            case TEAM -> {
                return routed(chat, null, team, options.reason(), options.excludeUserId());
            }
            case USER -> {
                User user = routing.userId() == null ? null : reachable.get(routing.userId());
                boolean ok = user != null && eligible(user, team, chat.getWorkspaceId())
                        && !user.getId().equals(options.excludeUserId());
                // the configured member can no longer take chats: the team queue
                return routed(chat, ok ? user : null, team, options.reason(), options.excludeUserId());
            }
            case ROUND_ROBIN -> {
                // the cursor is read fresh: a caller re-routing many chats holds a Team loaded before the first move
                Team current = teams.findById(team.getId()).orElse(team);
                User next = nextMember(current, reachable, chat.getWorkspaceId(), options.excludeUserId());
                if (next != null) {
                    // only the cursor: saving the whole team could revert an edit made since it was read
                    mongo.updateFirst(new Query(Criteria.where("_id").is(team.getId())),
                            new Update().set("roundRobinCursor", next.getId()), Team.class);
                }
                return routed(chat, next, team, options.reason(), options.excludeUserId());
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

    /** The agent who last handled this chat, when that was inside the sticky window and they can take it now. */
    private User stickyAgent(WaWebChat chat, InboxSetting setting, Team team, Map<String, User> reachable,
            String excludeUserId) {
        if (setting.getStickyDays() <= 0 || chat.getLastAgentUserId() == null || chat.getLastAgentAt() == null) {
            return null;
        }
        if (chat.getLastAgentUserId().equals(excludeUserId)) return null;
        Instant oldest = clock.instant().minus(Duration.ofDays(setting.getStickyDays()));
        if (chat.getLastAgentAt().isBefore(oldest)) return null;
        // the agent need not be in the owning team: a chat handed to another team sticks to who handled it (§6.5)
        User agent = reachable.get(chat.getLastAgentUserId());
        return agent != null && canTakeChats(agent, chat.getWorkspaceId()) ? agent : null;
    }

    /** The first eligible member after the team's cursor in {@code memberIds} order, wrapping; null when none is. */
    private User nextMember(Team team, Map<String, User> reachable, String workspaceId, String excludeUserId) {
        List<String> members = team.getMemberIds();
        if (members.isEmpty()) return null;
        int start = members.indexOf(team.getRoundRobinCursor()) + 1;
        for (int i = 0; i < members.size(); i++) {
            String id = members.get((start + i) % members.size());
            if (id.equals(excludeUserId)) continue;
            User u = reachable.get(id);
            if (u != null && eligible(u, team, workspaceId)) return u;
        }
        return null;
    }

    /** The users with access to the chat's workspace, by id. */
    private Map<String, User> reachable(WaWebChat chat) {
        return userAdmin.withAccessTo(chat.getCustomerId(), chat.getWorkspaceId()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
    }

    /** Active, not disabled anywhere that matters, and available right now (§3.2). */
    static boolean canTakeChats(User u, String workspaceId) {
        return u.isActive() && u.getDisabled() == null && UserAdminService.brandDisabled(u, workspaceId) == null
                && u.availabilityStatus() == Availability.Status.AVAILABLE;
    }

    /** {@link #canTakeChats} and a member of the team. */
    static boolean eligible(User u, Team team, String workspaceId) {
        return canTakeChats(u, workspaceId) && team.getMemberIds().contains(u.getId());
    }

    private WaWebChat routed(WaWebChat chat, User user, Team team, RoutedReason reason, String excludedUserId) {
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
        a.setReason(reason);
        a.setExcludedUserId(excludedUserId);
        a.setAt(chat.getAssignedAt());
        a = actions.save(a);
        publisher.publishEvent(new InboxEvents.ActionRecorded(chat, a));
        return chat;
    }
}
