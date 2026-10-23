package me.nageshk.omnichannel.inbox;

import java.time.Clock;
import me.nageshk.omnichannel.api.ValidationException;
import org.springframework.dao.OptimisticLockingFailureException;
import me.nageshk.omnichannel.phone.PhoneNumberRepository;
import me.nageshk.omnichannel.phone.PhoneNumber;
import me.nageshk.omnichannel.api.NotFoundException;
import me.nageshk.omnichannel.api.AssigneeCannotSeeNumberException;
import me.nageshk.omnichannel.access.Authority;
import me.nageshk.omnichannel.access.AccessService;
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
    private final PhoneNumberRepository phoneNumbers;
    private final AccessService access;
    private final ChatRoutingService routing;

    public ChatAssignmentService(WaWebChatRepository chats, WaWebChatActionRepository actions,
            InboxSettingService settings, UserRepository users, TeamRepository teams, UserAdminService userAdmin,
            ApplicationEventPublisher publisher, Clock clock, PhoneNumberRepository phoneNumbers, AccessService access,
            ChatRoutingService routing) {
        this.phoneNumbers = phoneNumbers;
        this.access = access;
        this.routing = routing;
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
     * Assigns the chat to a user, a team or both. A team given without a user keeps the current assignee only
     * while they are a member. A user given without a team keeps the chat's current team only if the user is a
     * member of it, else the team is cleared. A user who is not a member of the given team is dropped, leaving
     * the chat in the team queue.
     *
     * @throws ValidationException with {@code fields.userId} / {@code fields.teamId} for a target that is
     *         missing, not a user with access to the workspace, or not a team of it
     */
    public WaWebChat assign(AuthenticatedUser me, WaWebChat chat, String userId, String teamId) {
        boolean noUser = userId == null || userId.isBlank();
        boolean noTeam = teamId == null || teamId.isBlank();
        if (noUser && noTeam) throw new ValidationException("userId", "userId or teamId required");
        User user = noUser ? null : userWithAccess(chat, userId);
        if (user != null && !canSeeNumber(user, chat)) throw new AssigneeCannotSeeNumberException();
        Team team = noTeam ? null : teams.findByIdAndWorkspaceId(teamId, chat.getWorkspaceId())
                .orElseThrow(() -> new ValidationException("teamId", "unknown team"));
        if (noTeam && user != null && chat.getAssignedTeamId() != null) {
            team = teams.findByIdAndWorkspaceId(chat.getAssignedTeamId(), chat.getWorkspaceId())
                    .filter(t -> t.getMemberIds().contains(userId)).orElse(null);
        }
        if (noUser && team != null && chat.getAssignedUserId() != null) {
            user = users.findById(chat.getAssignedUserId()).orElse(null);
        }
        if (user != null && team != null && !team.getMemberIds().contains(user.getId())) user = null;

        chat.setAssignedUserId(user == null ? null : user.getId());
        chat.setAssignedTeamId(team == null ? null : team.getId());
        if (user != null) lastAgent(chat, user.getId());
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
        if (!settings.effectiveFor(chat).isKeepAssignmentOnClose()) {
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

    /** Like {@link #reopen(AuthenticatedUser, WaWebChat)} for a send that reopens the chat, a campaign's included. */
    public WaWebChat reopen(SendActor actor, WaWebChat chat) {
        if (chat.getStatus() == ChatStatus.OPEN) return chat;
        chat.setStatus(ChatStatus.OPEN);
        return commit(chat, action(actor.userId(), actor.displayName(), chat, ActionType.REOPENED), false);
    }

    /**
     * After a successful console send: gives an unassigned chat to the sender when the number's settings say
     * so. A chat that already has a user is left alone, whoever replies. Returns the chat as it now stands.
     */
    public WaWebChat claimOnReply(AuthenticatedUser me, WaWebChat chat) {
        if (chat.getAssignedUserId() != null) return chat;
        if (!settings.effectiveFor(chat).isClaimOnReply()) return chat;
        chat.setAssignedUserId(me.userId());
        lastAgent(chat, me.userId());
        WaWebChatAction action = action(me, chat, ActionType.ASSIGNED);
        action.setToUserId(me.userId());
        action.setToUserName(action.getByName());
        if (chat.getAssignedTeamId() != null) {
            action.setToTeamId(chat.getAssignedTeamId());
            teams.findById(chat.getAssignedTeamId()).ifPresent(t -> action.setToTeamName(t.getName()));
        }
        return commit(chat, action, true);
    }

    /**
     * The caller takes an unassigned chat for themselves (design §3.5); the team stays.
     *
     * @throws ChatTakenException when someone has it already, including a concurrent claim that saved first
     */
    public WaWebChat claim(AuthenticatedUser me, WaWebChat chat) {
        if (chat.getAssignedUserId() != null) throw new ChatTakenException();
        chat.setAssignedUserId(me.userId());
        lastAgent(chat, me.userId());
        WaWebChatAction action = action(me, chat, ActionType.ASSIGNED);
        action.setToUserId(me.userId());
        action.setToUserName(action.getByName());
        if (chat.getAssignedTeamId() != null) {
            action.setToTeamId(chat.getAssignedTeamId());
            teams.findById(chat.getAssignedTeamId()).ifPresent(t -> action.setToTeamName(t.getName()));
        }
        try {
            return commit(chat, action, true);
        } catch (OptimisticLockingFailureException raced) {
            throw new ChatTakenException();
        }
    }

    /**
     * The assignee passes the chat on (design §3.6); a {@code manager} (holder of {@code inbox:assign}) may do so
     * for any chat they see. Hand-backs route again without the caller and without stickiness; transfers record the
     * move and route under the target team's setting.
     *
     * @throws NotFoundException when a non-manager hands off a chat that is not assigned to them
     */
    public WaWebChat handoff(AuthenticatedUser me, WaWebChat chat, HandoffRequest req, boolean manager) {
        if (req == null || req.kind() == null) throw new ValidationException("kind", "required");
        if (!manager && !me.userId().equals(chat.getAssignedUserId())) throw new NotFoundException("chat not found");
        Team current = chat.getAssignedTeamId() == null ? owningTeam(chat)
                : teams.findByIdAndWorkspaceId(chat.getAssignedTeamId(), chat.getWorkspaceId()).orElse(null);
        switch (req.kind()) {
            case TO_USER -> {
                if (req.userId() == null || req.userId().isBlank()) throw new ValidationException("userId", "required");
                User target = userWithAccess(chat, req.userId());
                if (current == null || !current.getMemberIds().contains(target.getId()) || !canSeeNumber(target, chat)) {
                    throw new ValidationException("userId", "not a member of the chat's team");
                }
                chat.setAssignedUserId(target.getId());
                chat.setAssignedTeamId(current.getId());
                lastAgent(chat, target.getId());
                WaWebChatAction action = action(me, chat, ActionType.ASSIGNED);
                action.setToUserId(target.getId());
                action.setToUserName(target.getName());
                action.setToTeamId(current.getId());
                action.setToTeamName(current.getName());
                return commit(chat, action, true);
            }
            case TO_TEAM -> {
                boolean back = req.teamId() == null || req.teamId().isBlank()
                        || (current != null && req.teamId().equals(current.getId()));
                if (back) {
                    if (current == null) throw new ValidationException("teamId", "the chat has no team to hand back to");
                    return handBack(me, chat, current);
                }
                Team target = teams.findByIdAndWorkspaceId(req.teamId(), chat.getWorkspaceId())
                        .orElseThrow(() -> new ValidationException("teamId", "unknown team"));
                return transfer(me, chat, current, target);
            }
            default -> throw new ValidationException("kind", "unknown kind");
        }
    }

    private WaWebChat handBack(AuthenticatedUser me, WaWebChat chat, Team team) {
        chat.setAssignedUserId(null);
        chat.setAssignedTeamId(team.getId());
        if (settings.effective(chat.getWorkspaceId(), team.getId()).getRouting().mode() == RoutingMode.NONE) {
            // nothing routes on this team: the chat just stops being the caller's
            return commit(chat, action(me, chat, ActionType.UNASSIGNED), true);
        }
        WaWebChat routed = routing.route(chat, team,
                new ChatRoutingService.RoutingOptions(me.userId(), false, RoutedReason.HANDED_BACK));
        publisher.publishEvent(new InboxEvents.ChatChanged(routed));
        return routed;
    }

    private WaWebChat transfer(AuthenticatedUser me, WaWebChat chat, Team from, Team to) {
        chat.setAssignedUserId(null);
        chat.setAssignedTeamId(to.getId());
        WaWebChatAction action = action(me, chat, ActionType.TRANSFERRED);
        if (from != null) {
            action.setFromTeamId(from.getId());
            action.setFromTeamName(from.getName());
        }
        action.setToTeamId(to.getId());
        action.setToTeamName(to.getName());
        WaWebChat moved = commit(chat, action, true);
        WaWebChat routed = routing.route(moved, to, ChatRoutingService.RoutingOptions.DEFAULT);
        if (routed != moved) publisher.publishEvent(new InboxEvents.ChatChanged(routed));
        return routed;
    }

    /** The team that works the chat's number, or null. */
    private Team owningTeam(WaWebChat chat) {
        String teamId = phoneNumbers.findById(chat.getPhoneNumberId()).map(PhoneNumber::getTeamId).orElse(null);
        return teamId == null ? null : teams.findByIdAndWorkspaceId(teamId, chat.getWorkspaceId()).orElse(null);
    }

    /**
     * Whether {@code user} may open the chat's number (design §3.1): a member of its owning team, or someone who sees
     * every number. A number nobody owns is left to workspace access as before.
     */
    private boolean canSeeNumber(User user, WaWebChat chat) {
        Team owner = owningTeam(chat);
        if (owner == null || owner.getMemberIds().contains(user.getId())) return true;
        try {
            return access.inWorkspace(access.load(user), chat.getWorkspaceId()).contains(Authority.INBOX_VIEW_ALL.code());
        } catch (NotFoundException noAccess) {
            return false;
        }
    }

    private void lastAgent(WaWebChat chat, String userId) {
        chat.setLastAgentUserId(userId);
        chat.setLastAgentAt(clock.instant());
    }

    private User userWithAccess(WaWebChat chat, String userId) {
        return userAdmin.withAccessTo(chat.getCustomerId(), chat.getWorkspaceId()).stream()
                .filter(u -> userId.equals(u.getId())).findFirst()
                .orElseThrow(() -> new ValidationException("userId", "unknown user"));
    }

    private WaWebChatAction action(AuthenticatedUser me, WaWebChat chat, ActionType type) {
        return action(me.userId(), users.findById(me.userId()).map(User::getName).orElse(me.getUsername()), chat,
                type);
    }

    private WaWebChatAction action(String byUserId, String byName, WaWebChat chat, ActionType type) {
        WaWebChatAction a = new WaWebChatAction();
        a.setCustomerId(chat.getCustomerId());
        a.setWorkspaceId(chat.getWorkspaceId());
        a.setChatId(chat.getId());
        a.setType(type);
        a.setByUserId(byUserId);
        a.setByName(byName);
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
