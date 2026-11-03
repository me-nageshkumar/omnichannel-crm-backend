package me.nageshk.omnichannel.service.automation;

import me.nageshk.omnichannel.db.automation.AutomationAction;
import me.nageshk.omnichannel.db.automation.AutomationActionType;
import me.nageshk.omnichannel.db.automation.WhenAway;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatRepository;
import me.nageshk.omnichannel.db.tenant.Team;
import me.nageshk.omnichannel.db.tenant.TeamRepository;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.security.user.UserAdminService;
import me.nageshk.omnichannel.service.inbox.ChatAssignmentService;
import me.nageshk.omnichannel.service.inbox.ChatRoutingService;
import org.springframework.stereotype.Component;

/**
 * Gives the event's chat to an agent, a team's queue or a team's next member by round robin, replacing whatever the
 * inbox's routing did, and records it on the chat as routed by this rule.
 */
@Component
public class AssignChatHandler implements ActionHandler {

    private final WaWebChatRepository chats;
    private final TeamRepository teams;
    private final UserAdminService userAdmin;
    private final ChatAssignmentService assignment;
    private final ChatRoutingService routing;

    public AssignChatHandler(WaWebChatRepository chats, TeamRepository teams, UserAdminService userAdmin,
            ChatAssignmentService assignment, ChatRoutingService routing) {
        this.chats = chats;
        this.teams = teams;
        this.userAdmin = userAdmin;
        this.assignment = assignment;
        this.routing = routing;
    }

    @Override
    public AutomationActionType type() {
        return AutomationActionType.ASSIGN_CHAT;
    }

    @Override
    public ActionResult run(ActionContext ctx, AutomationAction action) {
        String chatId = ctx.data() == null || ctx.data().chat() == null ? null : ctx.data().chat().id();
        WaWebChat chat = chatId == null ? null : chats.findById(chatId).orElse(null);
        if (chat == null) return ActionResult.failed("No chat with this contact");
        if (action.mode() == null) return ActionResult.failed("No assignment mode");
        return switch (action.mode()) {
            case AGENT -> agent(ctx, action, chat);
            case TEAM_QUEUE -> {
                Team team = team(ctx, action);
                if (team == null) yield ActionResult.failed("The team no longer exists");
                routing.assignByAutomation(chat, null, team, ctx.ruleName());
                yield ActionResult.done("Waiting in " + team.getName() + "'s queue");
            }
            case TEAM_ROUND_ROBIN -> {
                Team team = team(ctx, action);
                if (team == null) yield ActionResult.failed("The team no longer exists");
                WaWebChat saved = routing.roundRobinByAutomation(chat, team, ctx.ruleName());
                yield saved.getAssignedUserId() == null
                        ? ActionResult.done("Nobody in " + team.getName() + " is available; waiting in its queue")
                        : ActionResult.done("Assigned to " + nameOf(saved, ctx) + " (round robin in "
                                + team.getName() + ")");
            }
        };
    }

    private ActionResult agent(ActionContext ctx, AutomationAction action, WaWebChat chat) {
        User user = action.userId() == null ? null : userAdmin.withAccessTo(ctx.customerId(), ctx.workspaceId())
                .stream().filter(u -> u.getId().equals(action.userId())).findFirst().orElse(null);
        if (user == null) return ActionResult.failed("The agent is no longer in the brand");
        if (!assignment.canSeeNumber(user, chat)) return ActionResult.failed(user.getName() + " cannot see this number");
        Team owning = routing.owningTeam(chat);
        if (!ChatRoutingService.canTakeChats(user, ctx.workspaceId()) && action.whenAway() == WhenAway.OWNING_TEAM_QUEUE
                && owning != null) {
            routing.assignByAutomation(chat, null, owning, ctx.ruleName());
            return ActionResult.done(user.getName() + " is away; waiting in " + owning.getName() + "'s queue");
        }
        boolean member = owning != null && owning.getMemberIds().contains(user.getId());
        routing.assignByAutomation(chat, user, member ? owning : null, ctx.ruleName());
        return ActionResult.done("Assigned to " + user.getName());
    }

    private Team team(ActionContext ctx, AutomationAction action) {
        return action.teamId() == null ? null
                : teams.findByIdAndWorkspaceId(action.teamId(), ctx.workspaceId()).orElse(null);
    }

    private String nameOf(WaWebChat chat, ActionContext ctx) {
        return userAdmin.withAccessTo(ctx.customerId(), ctx.workspaceId()).stream()
                .filter(u -> u.getId().equals(chat.getAssignedUserId())).map(User::getName).findFirst()
                .orElse(chat.getAssignedUserId());
    }
}
