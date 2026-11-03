package me.nageshk.omnichannel.app.automation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import me.nageshk.omnichannel.common.enablement.EnableLevel;
import me.nageshk.omnichannel.db.automation.AssignMode;
import me.nageshk.omnichannel.db.automation.AutomationAction;
import me.nageshk.omnichannel.db.automation.AutomationActionType;
import me.nageshk.omnichannel.db.automation.AutomationEventKind;
import me.nageshk.omnichannel.db.automation.EventData;
import me.nageshk.omnichannel.db.automation.StepOutcome;
import me.nageshk.omnichannel.db.automation.WhenAway;
import me.nageshk.omnichannel.db.enablement.Disabled;
import me.nageshk.omnichannel.db.inbox.ActionType;
import me.nageshk.omnichannel.db.inbox.RoutedReason;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatAction;
import me.nageshk.omnichannel.db.tenant.Team;
import me.nageshk.omnichannel.db.user.Availability;
import me.nageshk.omnichannel.db.user.BrandDisable;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.db.user.UserRepository;
import me.nageshk.omnichannel.db.user.WorkspaceRole;
import me.nageshk.omnichannel.service.automation.ActionContext;
import me.nageshk.omnichannel.service.automation.ActionResult;
import me.nageshk.omnichannel.service.automation.AssignChatHandler;
import me.nageshk.omnichannel.service.inbox.InboxSettingService;
import me.nageshk.omnichannel.service.inbox.RoutingSetting;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/** The assign-chat action replaces what routing did and is recorded on the chat as routed by automation. */
class AssignChatActionIT extends AutomationTestBase {

    private static final String ASHA = "919811100001@c.us";

    @Autowired AssignChatHandler handler;
    @Autowired InboxSettingService settings;
    @Autowired UserRepository users;

    private void routeTo(me.nageshk.omnichannel.db.inbox.RoutingMode mode, User user) {
        settings.putOverride(brand.getId(), support.getId(), new RoutingSetting.Request(true,
                mode == me.nageshk.omnichannel.db.inbox.RoutingMode.ROUND_ROBIN,
                mode == me.nageshk.omnichannel.db.inbox.RoutingMode.USER ? user.getId() : null, null, null, null));
    }

    /** A chat on n1 that routing gave to agent2. */
    private WaWebChat routedChat() {
        routeTo(me.nageshk.omnichannel.db.inbox.RoutingMode.USER, agent2);
        WaWebChat chat = incoming(n1, ASHA, "chat", "Hi", "Asha");
        assertThat(chat.getAssignedUserId()).isEqualTo(agent2.getId());
        return chat;
    }

    private AutomationAction assign(AssignMode mode, User user, String teamId, WhenAway whenAway) {
        return new AutomationAction(AutomationActionType.ASSIGN_CHAT, null, null, mode,
                user == null ? null : user.getId(), teamId, whenAway, null, null, null);
    }

    private ActionContext ctx(WaWebChat chat) {
        EventData.ChatData c = chat == null ? null
                : new EventData.ChatData(chat.getId(), false, chat.getStatus(), null, null);
        return new ActionContext(brand.getId(), acme.getId(), AutomationEventKind.MESSAGE_RECEIVED, "m-1",
                new EventData(c, null, null, null, null, null, null, null), "r1", "Welcome rule", false);
    }

    private WaWebChat stored(WaWebChat chat) {
        return chats.findById(chat.getId()).orElseThrow();
    }

    private WaWebChatAction newest(WaWebChat chat) {
        return mongo.findOne(new Query(Criteria.where("chatId").is(chat.getId()))
                .with(Sort.by(Sort.Direction.DESC, "at", "_id")), WaWebChatAction.class);
    }

    private void away(User u) {
        u.setAvailability(new Availability(Availability.Status.AWAY, clock.instant()));
        users.save(u);
    }

    private void assertAutomation(WaWebChat chat) {
        WaWebChatAction a = newest(chat);
        assertThat(a.getType()).isEqualTo(ActionType.ROUTED);
        assertThat(a.getReason()).isEqualTo(RoutedReason.AUTOMATION);
        assertThat(a.getReasonDetail()).isEqualTo("Welcome rule");
        assertThat(a.getByName()).isEqualTo("Automation: Welcome rule");
    }

    @Test
    void agentReplacesRouting() {
        WaWebChat chat = routedChat();

        ActionResult r = handler.run(ctx(chat), assign(AssignMode.AGENT, agent, null, WhenAway.KEEP_WITH_AGENT));

        assertThat(r.outcome()).isEqualTo(StepOutcome.DONE);
        assertThat(r.reason()).isEqualTo("Assigned to " + agent.getName());
        WaWebChat after = stored(chat);
        assertThat(after.getAssignedUserId()).isEqualTo(agent.getId());
        assertThat(after.getAssignedTeamId()).isEqualTo(support.getId());
        assertAutomation(chat);
        assertThat(newest(chat).getToUserId()).isEqualTo(agent.getId());

        JsonNode thread = call(HttpMethod.GET, "/api/workspaces/" + brand.getId() + "/inbox/chats/" + chat.getId()
                + "/thread", manager, null).getBody();
        JsonNode last = null;
        for (JsonNode i : thread.get("items")) if (!i.get("action").isNull()) last = i.get("action");
        assertThat(last.get("reason").asString()).isEqualTo("AUTOMATION");
        assertThat(last.get("reasonDetail").asString()).isEqualTo("Welcome rule");
    }

    @Test
    void awayAgentGoesToOwningTeamQueue() {
        WaWebChat chat = routedChat();
        away(agent);

        ActionResult r = handler.run(ctx(chat), assign(AssignMode.AGENT, agent, null, WhenAway.OWNING_TEAM_QUEUE));

        assertThat(r.outcome()).isEqualTo(StepOutcome.DONE);
        assertThat(r.reason()).isEqualTo(agent.getName() + " is away; waiting in support's queue");
        WaWebChat after = stored(chat);
        assertThat(after.getAssignedUserId()).isNull();
        assertThat(after.getAssignedTeamId()).isEqualTo(support.getId());
        assertAutomation(chat);
    }

    @Test
    void awayAgentKept() {
        WaWebChat chat = routedChat();
        away(agent);

        ActionResult r = handler.run(ctx(chat), assign(AssignMode.AGENT, agent, null, WhenAway.KEEP_WITH_AGENT));

        assertThat(r.reason()).isEqualTo("Assigned to " + agent.getName());
        assertThat(stored(chat).getAssignedUserId()).isEqualTo(agent.getId());
        assertThat(stored(chat).getAssignedTeamId()).isEqualTo(support.getId());
    }

    @Test
    void awayAgentKeptWhenNumberHasNoTeam() {
        WaWebChat chat = incoming(n2, ASHA, "chat", "Hi", "Asha");
        away(agent);

        ActionResult r = handler.run(ctx(chat), assign(AssignMode.AGENT, agent, null, WhenAway.OWNING_TEAM_QUEUE));

        assertThat(r.outcome()).isEqualTo(StepOutcome.DONE);
        assertThat(r.reason()).isEqualTo("Assigned to " + agent.getName());
        WaWebChat after = stored(chat);
        assertThat(after.getAssignedUserId()).isEqualTo(agent.getId());
        assertThat(after.getAssignedTeamId()).isNull();
        assertAutomation(chat);
    }

    @Test
    void agentWhoCannotSeeNumberFails() {
        WaWebChat chat = routedChat();

        ActionResult r = handler.run(ctx(chat), assign(AssignMode.AGENT, outsider, null, WhenAway.KEEP_WITH_AGENT));

        assertThat(r.outcome()).isEqualTo(StepOutcome.FAILED);
        assertThat(r.reason()).isEqualTo(outsider.getName() + " cannot see this number");
        assertThat(stored(chat).getAssignedUserId()).isEqualTo(agent2.getId());
        assertThat(newest(chat).getReason()).isNotEqualTo(RoutedReason.AUTOMATION);
    }

    @Test
    void teamQueue() {
        WaWebChat chat = routedChat();

        ActionResult r = handler.run(ctx(chat), assign(AssignMode.TEAM_QUEUE, null, support.getId(), null));

        assertThat(r.outcome()).isEqualTo(StepOutcome.DONE);
        assertThat(r.reason()).isEqualTo("Waiting in support's queue");
        WaWebChat after = stored(chat);
        assertThat(after.getAssignedUserId()).isNull();
        assertThat(after.getAssignedTeamId()).isEqualTo(support.getId());
        assertAutomation(chat);
    }

    @Test
    void roundRobinSharesTheTeamCursor() {
        WaWebChat chat = routedChat();
        Team t = teams.findById(support.getId()).orElseThrow();
        t.setRoundRobinCursor(agent.getId());
        teams.save(t);

        ActionResult r = handler.run(ctx(chat), assign(AssignMode.TEAM_ROUND_ROBIN, null, support.getId(), null));

        assertThat(r.outcome()).isEqualTo(StepOutcome.DONE);
        assertThat(r.reason()).isEqualTo("Assigned to " + agent2.getName() + " (round robin in support)");
        WaWebChat after = stored(chat);
        assertThat(after.getAssignedUserId()).isEqualTo(agent2.getId());
        assertThat(after.getAssignedTeamId()).isEqualTo(support.getId());
        assertAutomation(chat);
        assertThat(teams.findById(support.getId()).orElseThrow().getRoundRobinCursor()).isEqualTo(agent2.getId());

        routeTo(me.nageshk.omnichannel.db.inbox.RoutingMode.ROUND_ROBIN, null);
        WaWebChat next = incoming(n1, "919811100002@c.us", "chat", "Hi", "Bo");
        assertThat(next.getAssignedUserId()).isEqualTo(agent.getId());
    }

    @Test
    void roundRobinNobodyAvailable() {
        WaWebChat chat = routedChat();
        away(agent);
        away(agent2);

        ActionResult r = handler.run(ctx(chat), assign(AssignMode.TEAM_ROUND_ROBIN, null, support.getId(), null));

        assertThat(r.outcome()).isEqualTo(StepOutcome.DONE);
        assertThat(r.reason()).isEqualTo("Nobody in support is available; waiting in its queue");
        WaWebChat after = stored(chat);
        assertThat(after.getAssignedUserId()).isNull();
        assertThat(after.getAssignedTeamId()).isEqualTo(support.getId());
        assertAutomation(chat);
    }

    @Test
    void removedAgentFails() {
        WaWebChat chat = routedChat();
        agent.setWorkspaceRoles(List.<WorkspaceRole>of());
        users.save(agent);

        ActionResult r = handler.run(ctx(chat), assign(AssignMode.AGENT, agent, null, WhenAway.KEEP_WITH_AGENT));

        assertThat(r.outcome()).isEqualTo(StepOutcome.FAILED);
        assertThat(r.reason()).isEqualTo("The agent is no longer in the brand");
        assertThat(stored(chat).getAssignedUserId()).isEqualTo(agent2.getId());
    }

    @Test
    void disabledAgentFails() {
        WaWebChat chat = routedChat();
        AutomationAction action = assign(AssignMode.AGENT, agent, null, WhenAway.KEEP_WITH_AGENT);

        // deactivated
        User u = users.findById(agent.getId()).orElseThrow();
        u.setActive(false);
        users.save(u);
        ActionResult r = handler.run(ctx(chat), action);
        assertThat(r.outcome()).isEqualTo(StepOutcome.FAILED);
        assertThat(r.reason()).isEqualTo("The agent is no longer in the brand");
        assertThat(stored(chat).getAssignedUserId()).isEqualTo(agent2.getId());

        // disabled in the customer
        u.setActive(true);
        u.setDisabled(new Disabled(manager.getId(), EnableLevel.COMPANY, clock.instant()));
        users.save(u);
        r = handler.run(ctx(chat), action);
        assertThat(r.outcome()).isEqualTo(StepOutcome.FAILED);
        assertThat(r.reason()).isEqualTo("The agent is no longer in the brand");
        assertThat(stored(chat).getAssignedUserId()).isEqualTo(agent2.getId());

        // disabled in this brand
        u.setDisabled(null);
        u.setBrandDisables(new ArrayList<>(List.of(new BrandDisable(brand.getId(),
                new Disabled(manager.getId(), EnableLevel.BRAND, clock.instant())))));
        users.save(u);
        r = handler.run(ctx(chat), action);
        assertThat(r.outcome()).isEqualTo(StepOutcome.FAILED);
        assertThat(r.reason()).isEqualTo("The agent is no longer in the brand");
        assertThat(stored(chat).getAssignedUserId()).isEqualTo(agent2.getId());
    }

    @Test
    void deletedTeamFails() {
        WaWebChat chat = routedChat();

        ActionResult r = handler.run(ctx(chat), assign(AssignMode.TEAM_QUEUE, null, "gone", null));

        assertThat(r.outcome()).isEqualTo(StepOutcome.FAILED);
        assertThat(r.reason()).isEqualTo("The team no longer exists");
        assertThat(stored(chat).getAssignedUserId()).isEqualTo(agent2.getId());
    }

    @Test
    void noChatFails() {
        ActionResult r = handler.run(ctx(null), assign(AssignMode.TEAM_QUEUE, null, support.getId(), null));

        assertThat(r.outcome()).isEqualTo(StepOutcome.FAILED);
        assertThat(r.reason()).isEqualTo("No chat with this contact");
    }
}
