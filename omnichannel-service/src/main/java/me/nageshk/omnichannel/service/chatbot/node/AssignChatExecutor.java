package me.nageshk.omnichannel.service.chatbot.node;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import me.nageshk.omnichannel.db.automation.WhenAway;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.tenant.Team;
import me.nageshk.omnichannel.db.tenant.TeamRepository;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.security.user.UserAdminService;
import me.nageshk.omnichannel.service.automation.AssignTargets;
import me.nageshk.omnichannel.service.chatbot.run.NodeFailed;
import me.nageshk.omnichannel.service.chatbot.run.RunChat;
import me.nageshk.omnichannel.service.inbox.ChatAssignmentService;
import me.nageshk.omnichannel.service.inbox.ChatRoutingService;
import me.nageshk.omnichannel.service.chatbot.flow.Flow;
import me.nageshk.omnichannel.service.chatbot.flow.FlowNode;
import me.nageshk.omnichannel.service.chatbot.flow.FlowParser;
import me.nageshk.omnichannel.service.chatbot.flow.NodeType;
import me.nageshk.omnichannel.service.chatbot.flow.settings.AssignChatSettings;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * Hand over to agent (spec 2026-11-18 §5.4; type code {@code assign_chat}): gives the chat to people, by the number's
 * own routing or the automation action's choices, and ends the run. Nothing runs after it.
 */
@Component
public class AssignChatExecutor extends BaseExecutor {

    private final AssignTargets targets;
    private final TeamRepository teams;
    private final UserAdminService userAdmin;
    private final ChatAssignmentService assignment;
    private final ChatRoutingService routing;
    private final RunChat runChat;
    private final me.nageshk.omnichannel.service.inbox.BotChats botChats;
    private final org.springframework.context.ApplicationEventPublisher publisher;

    public AssignChatExecutor(FlowParser parser, AssignTargets targets, TeamRepository teams, UserAdminService userAdmin,
            ChatAssignmentService assignment, ChatRoutingService routing, RunChat runChat,
            me.nageshk.omnichannel.service.inbox.BotChats botChats,
            org.springframework.context.ApplicationEventPublisher publisher) {
        super(parser);
        this.botChats = botChats;
        this.publisher = publisher;
        this.targets = targets;
        this.teams = teams;
        this.userAdmin = userAdmin;
        this.assignment = assignment;
        this.routing = routing;
        this.runChat = runChat;
    }

    @Override
    public List<String> outputs(FlowNode node, me.nageshk.omnichannel.service.chatbot.node.ValidationContext ctx) {
        return List.of();
    }

    @Override
    public StepResult run(RunContext ctx, FlowNode node) {
        if (ctx.chat() != null) {
            // out of the chatbot's hands first, so the assignment below lands on an open chat
            if (botChats.open(ctx.chat().getId()).isEmpty()) botChats.markHandedOver(ctx.chat().getId());
        }
        // start from the stored chat (an earlier send changed it); a message landing in between gets one more try
        try {
            try {
                return assign(ctx, node, runChat.fresh(ctx));
            } catch (OptimisticLockingFailureException e) {
                return assign(ctx, node, runChat.fresh(ctx));
            }
        } catch (RuntimeException failed) {
            // the chatbot let go already: the number's routing still gives the chat to the team (spec §5.3, FAILED)
            toTheTeam(ctx);
            throw failed;
        }
    }

    private void toTheTeam(RunContext ctx) {
        try {
            WaWebChat chat = runChat.fresh(ctx);
            if (chat == null || chat.getAssignedUserId() != null || chat.getAssignedTeamId() != null) return;
            String name = ctx.run().getChatbotName();
            WaWebChat saved = routing.route(chat, new ChatRoutingService.RoutingOptions(null, true,
                    me.nageshk.omnichannel.db.inbox.RoutedReason.CHATBOT_ENDED, "Chatbot: " + name, name));
            publisher.publishEvent(new me.nageshk.omnichannel.service.inbox.InboxEvents.ChatChanged(saved));
        } catch (RuntimeException e) {
            // the sweep and the team's Unassigned list still have it
        }
    }

    private StepResult assign(RunContext ctx, FlowNode node, WaWebChat chat) {
        AssignChatSettings s = settings(node, AssignChatSettings.class);
        String name = ctx.run().getChatbotName();
        if (s.mode() == null) throw new NodeFailed("no assignment mode");
        switch (s.mode()) {
            case NUMBER_ROUTING -> {
                if (chat.getAssignedUserId() != null) {
                    String holder = userAdmin.withAccessTo(ctx.run().getCustomerId(), ctx.workspaceId()).stream()
                            .filter(u -> u.getId().equals(chat.getAssignedUserId())).map(User::getName).findFirst()
                            .orElse("the agent");
                    return StepResult.handOver(assigned(null, null), "Stays with " + holder);
                }
                WaWebChat saved = routing.route(chat, new ChatRoutingService.RoutingOptions(null, true,
                        me.nageshk.omnichannel.db.inbox.RoutedReason.CHATBOT_HANDOVER, "Chatbot: " + name, name));
                ctx.chat(saved);
                publisher.publishEvent(new me.nageshk.omnichannel.service.inbox.InboxEvents.ChatChanged(saved));
                Team team = saved.getAssignedTeamId() == null ? null
                        : teams.findByIdAndWorkspaceId(saved.getAssignedTeamId(), ctx.workspaceId()).orElse(null);
                User picked = saved.getAssignedUserId() == null ? null
                        : userAdmin.withAccessTo(ctx.run().getCustomerId(), ctx.workspaceId()).stream()
                                .filter(u -> u.getId().equals(saved.getAssignedUserId())).findFirst().orElse(null);
                String summary = picked != null ? "Assigned to " + picked.getName()
                        : team != null ? "Waiting in " + team.getName() + "'s queue" : "Open, waiting for the team";
                return StepResult.handOver(assigned(picked, team), summary);
            }
            case AGENT -> {
                User user = s.userId() == null ? null : userAdmin.withAccessTo(ctx.run().getCustomerId(), ctx.workspaceId())
                        .stream().filter(u -> u.getId().equals(s.userId())).findFirst().orElse(null);
                if (user == null || !user.isActive() || user.getDisabled() != null
                        || UserAdminService.brandDisabled(user, ctx.workspaceId()) != null) {
                    throw new NodeFailed("the agent is no longer in the brand");
                }
                if (!assignment.canSeeNumber(user, chat)) throw new NodeFailed(user.getName() + " cannot see this number");
                Team owning = routing.owningTeam(chat);
                if (!ChatRoutingService.canTakeChats(user, ctx.workspaceId()) && s.whenAway() == WhenAway.OWNING_TEAM_QUEUE
                        && owning != null) {
                    WaWebChat saved = routing.assignByChatbot(chat, null, owning, name);
                    ctx.chat(saved);
                    return StepResult.handOver(assigned(null, owning), user.getName() + " is away; waiting in " + owning.getName() + "'s queue");
                }
                boolean member = owning != null && owning.getMemberIds().contains(user.getId());
                WaWebChat saved = routing.assignByChatbot(chat, user, member ? owning : null, name);
                ctx.chat(saved);
                return StepResult.handOver(assigned(user, member ? owning : null), "Assigned to " + user.getName());
            }
            case TEAM_QUEUE -> {
                Team team = team(s, ctx);
                WaWebChat saved = routing.assignByChatbot(chat, null, team, name);
                ctx.chat(saved);
                return StepResult.handOver(assigned(null, team), "Waiting in " + team.getName() + "'s queue");
            }
            default -> {
                Team team = team(s, ctx);
                WaWebChat saved = routing.roundRobinByChatbot(chat, team, name);
                ctx.chat(saved);
                User picked = saved.getAssignedUserId() == null ? null : userAdmin.withAccessTo(ctx.run().getCustomerId(), ctx.workspaceId())
                        .stream().filter(u -> u.getId().equals(saved.getAssignedUserId())).findFirst().orElse(null);
                return StepResult.handOver(assigned(picked, team), picked == null
                        ? "Nobody in " + team.getName() + " is available; waiting in its queue"
                        : "Assigned to " + picked.getName() + " (round robin in " + team.getName() + ")");
            }
        }
    }

    private Team team(AssignChatSettings s, RunContext ctx) {
        return s.teamId() == null ? null : teams.findByIdAndWorkspaceId(s.teamId(), ctx.workspaceId())
                .orElseThrow(() -> new NodeFailed("the team no longer exists"));
    }

    private static Map<String, Object> assigned(User user, Team team) {
        Map<String, Object> to = new LinkedHashMap<>();
        to.put("userId", user == null ? null : user.getId());
        to.put("userName", user == null ? null : user.getName());
        to.put("teamId", team == null ? null : team.getId());
        to.put("teamName", team == null ? null : team.getName());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("assignedTo", to);
        return result;
    }

    @Override
    public NodeType type() {
        return NodeType.ASSIGN_CHAT;
    }

    @Override
    public List<Problem> validate(FlowNode node, Flow flow, ValidationContext ctx) {
        AssignChatSettings s = settings(node, AssignChatSettings.class);
        Problems p = new Problems(node);
        targets.check(ctx.workspaceId(), s.mode(), s.userId(), s.teamId()).forEach(p::error);
        return p.list;
    }
}
