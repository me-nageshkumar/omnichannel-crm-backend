package me.nageshk.omnichannel.inbox;

import java.time.Clock;
import me.nageshk.omnichannel.api.ValidationException;
import me.nageshk.omnichannel.auth.AuthenticatedUser;
import me.nageshk.omnichannel.tenant.Team;
import me.nageshk.omnichannel.tenant.TeamRepository;
import me.nageshk.omnichannel.user.User;
import me.nageshk.omnichannel.user.UserAdminService;
import me.nageshk.omnichannel.user.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Manual changes to a chat's assignment and open/closed state. Each one saves the chat, records a
 * {@link WaWebChatAction} and publishes {@link InboxEvents.ChatChanged} and {@link InboxEvents.ActionRecorded}.
 * Callers check the authority and that the chat is visible to {@code me}.
 */
@Service
public class ChatAssignmentService {

    private final WaWebChatRepository chats;
    private final WaWebChatActionRepository actions;
    private final InboxSettingService settings;
    private final UserRepository users;
    private final TeamRepository teams;
    private final UserAdminService userAdmin;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    public ChatAssignmentService(WaWebChatRepository chats, WaWebChatActionRepository actions,
            InboxSettingService settings, UserRepository users, TeamRepository teams, UserAdminService userAdmin,
            ApplicationEventPublisher publisher, Clock clock) {
        this.chats = chats;
        this.actions = actions;
        this.settings = settings;
        this.users = users;
        this.teams = teams;
        this.userAdmin = userAdmin;
        this.publisher = publisher;
        this.clock = clock;
    }

    /**
     * Assigns the chat to a user, a team or both, replacing the previous assignment. A team given without a user
     * keeps the current assignee only while they are a member; a user who is not a member of the given team is
     * dropped, leaving the chat in the team queue.
     *
     * @throws ValidationException with {@code fields.userId} / {@code fields.teamId} for a target that is
     *         missing, not a user with access to the workspace, or not a team of it
     */
    public WaWebChat assign(AuthenticatedUser me, WaWebChat chat, String userId, String teamId) {
        boolean noUser = userId == null || userId.isBlank();
        boolean noTeam = teamId == null || teamId.isBlank();
        if (noUser && noTeam) throw new ValidationException("userId", "userId or teamId required");
        User user = noUser ? null : userWithAccess(chat, userId);
        Team team = noTeam ? null : teams.findByIdAndWorkspaceId(teamId, chat.getWorkspaceId())
                .orElseThrow(() -> new ValidationException("teamId", "unknown team"));
        if (noUser && team != null && chat.getAssignedUserId() != null) {
            user = users.findById(chat.getAssignedUserId()).orElse(null);
        }
        if (user != null && team != null && !team.getMemberIds().contains(user.getId())) user = null;

        chat.setAssignedUserId(user == null ? null : user.getId());
        chat.setAssignedTeamId(team == null ? null : team.getId());
        WaWebChatAction action = action(me, chat, ActionType.ASSIGNED);
        action.setToUserId(chat.getAssignedUserId());
        action.setToUserName(user == null ? null : user.getName());
        action.setToTeamId(chat.getAssignedTeamId());
        action.setToTeamName(team == null ? null : team.getName());
        return commit(chat, action, true);
    }

    public WaWebChat unassign(AuthenticatedUser me, WaWebChat chat) {
        chat.setAssignedUserId(null);
        chat.setAssignedTeamId(null);
        return commit(chat, action(me, chat, ActionType.UNASSIGNED), true);
    }

    /** Closes the chat and clears its assignment unless the number keeps it; closing a closed chat does nothing. */
    public WaWebChat close(AuthenticatedUser me, WaWebChat chat) {
        if (chat.getStatus() == ChatStatus.CLOSED) return chat;
        chat.setStatus(ChatStatus.CLOSED);
        boolean cleared = false;
        if (!settings.get(chat.getWorkspaceId(), chat.getPhoneNumberId()).isKeepAssignmentOnClose()) {
            cleared = chat.getAssignedUserId() != null || chat.getAssignedTeamId() != null;
            chat.setAssignedUserId(null);
            chat.setAssignedTeamId(null);
        }
        return commit(chat, action(me, chat, ActionType.CLOSED), cleared);
    }

    /** Reopens by hand, keeping the assignment; reopening an open chat does nothing. */
    public WaWebChat reopen(AuthenticatedUser me, WaWebChat chat) {
        if (chat.getStatus() == ChatStatus.OPEN) return chat;
        chat.setStatus(ChatStatus.OPEN);
        return commit(chat, action(me, chat, ActionType.REOPENED), false);
    }

    /**
     * After a successful console send: gives an unassigned chat to the sender when the number's settings say
     * so. A chat that already has a user is left alone, whoever replies. Returns the chat as it now stands.
     */
    public WaWebChat claimOnReply(AuthenticatedUser me, WaWebChat chat) {
        if (chat.getAssignedUserId() != null) return chat;
        if (!settings.get(chat.getWorkspaceId(), chat.getPhoneNumberId()).isClaimOnReply()) return chat;
        chat.setAssignedUserId(me.userId());
        WaWebChatAction action = action(me, chat, ActionType.ASSIGNED);
        action.setToUserId(me.userId());
        action.setToUserName(action.getByName());
        if (chat.getAssignedTeamId() != null) {
            action.setToTeamId(chat.getAssignedTeamId());
            teams.findById(chat.getAssignedTeamId()).ifPresent(t -> action.setToTeamName(t.getName()));
        }
        return commit(chat, action, true);
    }

    private User userWithAccess(WaWebChat chat, String userId) {
        return userAdmin.withAccessTo(chat.getCustomerId(), chat.getWorkspaceId()).stream()
                .filter(u -> userId.equals(u.getId())).findFirst()
                .orElseThrow(() -> new ValidationException("userId", "unknown user"));
    }

    private WaWebChatAction action(AuthenticatedUser me, WaWebChat chat, ActionType type) {
        WaWebChatAction a = new WaWebChatAction();
        a.setCustomerId(chat.getCustomerId());
        a.setWorkspaceId(chat.getWorkspaceId());
        a.setChatId(chat.getId());
        a.setType(type);
        a.setByUserId(me.userId());
        a.setByName(users.findById(me.userId()).map(User::getName).orElse(me.getUsername()));
        a.setAt(clock.instant());
        return a;
    }

    private WaWebChat commit(WaWebChat chat, WaWebChatAction action, boolean assignmentChanged) {
        if (assignmentChanged) chat.setAssignedAt(action.getAt());
        chat = chats.save(chat);
        action = actions.save(action);
        publisher.publishEvent(new InboxEvents.ChatChanged(chat));
        publisher.publishEvent(new InboxEvents.ActionRecorded(chat, action));
        return chat;
    }
}
