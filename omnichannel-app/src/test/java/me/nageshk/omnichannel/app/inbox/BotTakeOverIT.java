package me.nageshk.omnichannel.app.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import me.nageshk.omnichannel.app.automation.AutomationDevice;
import me.nageshk.omnichannel.app.chatbot.ChatbotTestBase;
import me.nageshk.omnichannel.db.chatbot.Chatbot;
import me.nageshk.omnichannel.db.chatbot.ChatbotRepository;
import me.nageshk.omnichannel.db.chatbot.ChatbotRun;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunRepository;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunStatus;
import me.nageshk.omnichannel.db.chatbot.ChatbotSettingsLayer;
import me.nageshk.omnichannel.db.chatbot.IfRunning;
import me.nageshk.omnichannel.db.chatbot.StartedBy;
import me.nageshk.omnichannel.db.contact.ContactRepository;
import me.nageshk.omnichannel.db.inbox.ActionType;
import me.nageshk.omnichannel.db.inbox.ChatStatus;
import me.nageshk.omnichannel.db.inbox.RoutedReason;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatAction;
import me.nageshk.omnichannel.service.chatbot.run.ChatbotRunService;
import me.nageshk.omnichannel.service.inbox.CampaignActor;
import me.nageshk.omnichannel.service.inbox.InboxSendService;
import me.nageshk.omnichannel.service.inbox.InboxSettingService;
import me.nageshk.omnichannel.service.inbox.RoutingSetting;
import me.nageshk.omnichannel.service.inbox.SendRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** Spec 2026-11-18 §5.3: what a person's reply, assignment, claim, close or stop does to a chat a chatbot holds. */
class BotTakeOverIT extends ChatbotTestBase {

    static final String VEENU = "919799750743@c.us";

    @Autowired ChatbotRunService runs;
    @Autowired ChatbotRunRepository runRepo;
    @Autowired ChatbotRepository chatbots;
    @Autowired ContactRepository contacts;
    @Autowired InboxSettingService settings;
    @Autowired InboxSendService sender;

    private static Map<String, Object> askFlow() {
        return flow(List.of(node("q", "ask_question", "AskName", Map.of("text", "Name?", "answerType", "TEXT"))), List.of());
    }

    /** A chat in the team's queue that a waiting chatbot run has taken. */
    private ChatbotRun botChat(boolean stopOnAgentReply) throws Exception {
        WaWebChat c = incoming(n1, VEENU, "chat", "hello", "Veenu");
        c = chats.findById(c.getId()).orElseThrow();
        c.setAssignedUserId(null);
        c = chats.save(c);
        String id = createChatbot("Welcome", askFlow()).get("id").asString();
        if (!stopOnAgentReply) {
            Chatbot b = chatbots.findById(id).orElseThrow();
            b.setSettings(new ChatbotSettingsLayer(null, null, null, null, null, null, null, null, false, null, null));
            chatbots.save(b);
        }
        ChatbotRunService.StartResult r = runs.start(brand.getId(), id, contacts.findById(c.getContactId()).orElseThrow(),
                c, StartedBy.test(manager.getId()), null, IfRunning.SKIP, true);
        r.settled().get(5, TimeUnit.SECONDS);
        assertThat(chats.findById(c.getId()).orElseThrow().getStatus()).isEqualTo(ChatStatus.BOT);
        return runRepo.findById(r.run().getId()).orElseThrow();
    }

    private String chatPath(ChatbotRun run) {
        return "/api/workspaces/" + brand.getId() + "/inbox/chats/" + run.getChatId();
    }

    private WaWebChat chatOf(ChatbotRun run) {
        return chats.findById(run.getChatId()).orElseThrow();
    }

    private ChatbotRun fresh(ChatbotRun run) {
        return runRepo.findById(run.getId()).orElseThrow();
    }

    private List<WaWebChatAction> endedRoutes(ChatbotRun run) {
        return mongo.findAll(WaWebChatAction.class).stream().filter(a -> a.getChatId().equals(run.getChatId())
                && a.getType() == ActionType.ROUTED && a.getReason() == RoutedReason.CHATBOT_ENDED).toList();
    }

    private ResponseEntity<JsonNode> reply(ChatbotRun run, me.nageshk.omnichannel.db.user.User who) {
        return call(HttpMethod.POST, chatPath(run) + "/messages", who, Map.of("text", "Hi, Meera here"));
    }

    @Test
    void aReplyTakesTheChatOverAndEndsTheChatbot() throws Exception {
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = botChat(true);
            assertThat(reply(run, manager).getStatusCode()).isEqualTo(HttpStatus.OK);
            await(() -> fresh(run).getStatus() == ChatbotRunStatus.HANDED_OVER);
            WaWebChat after = chatOf(run);
            assertThat(after.getStatus()).isEqualTo(ChatStatus.OPEN);
            assertThat(after.getAssignedUserId()).isEqualTo(manager.getId());
            assertThat(after.getHandedOverAt()).isNotNull();
            assertThat(endedRoutes(run)).isEmpty();
        }
    }

    @Test
    void aReplyTakesItEvenWhenClaimOnReplyIsOff() throws Exception {
        settings.putOverride(brand.getId(), support.getId(), new RoutingSetting.Request(true, false, null, null, false, null));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = botChat(true);
            assertThat(reply(run, manager).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(chatOf(run).getAssignedUserId()).isEqualTo(manager.getId());
        }
    }

    @Test
    void withStopOnAgentReplyOffTheRunGoesOn() throws Exception {
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = botChat(false);
            assertThat(reply(run, manager).getStatusCode()).isEqualTo(HttpStatus.OK);
            Thread.sleep(300);
            assertThat(fresh(run).getStatus()).isEqualTo(ChatbotRunStatus.WAITING);
            assertThat(chatOf(run).getStatus()).isEqualTo(ChatStatus.OPEN);
            assertThat(chatOf(run).getAssignedUserId()).isEqualTo(manager.getId());
        }
    }

    @Test
    void anAgentWithoutThePermissionCannotReply() throws Exception {
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = botChat(true);
            assertThat(reply(run, agent).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(chatOf(run).getStatus()).isEqualTo(ChatStatus.BOT);
        }
    }

    @Test
    void assigningToATeamOpensIt() throws Exception {
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = botChat(true);
            ResponseEntity<JsonNode> res = call(HttpMethod.POST, chatPath(run) + "/assign", manager,
                    Map.of("teamId", support.getId()));
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            WaWebChat after = chatOf(run);
            assertThat(after.getStatus()).isEqualTo(ChatStatus.OPEN);
            assertThat(after.getAssignedTeamId()).isEqualTo(support.getId());
            assertThat(after.getHandedOverAt()).isNotNull();
            assertThat(fresh(run).getStatus()).isEqualTo(ChatbotRunStatus.WAITING);
        }
    }

    @Test
    void closingStopsTheChatbot() throws Exception {
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = botChat(true);
            assertThat(call(HttpMethod.POST, chatPath(run) + "/close", manager, null).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
            assertThat(chatOf(run).getStatus()).isEqualTo(ChatStatus.CLOSED);
            assertThat(fresh(run).getStatus()).isEqualTo(ChatbotRunStatus.STOPPED);
            assertThat(endedRoutes(run)).isEmpty();
        }
    }

    @Test
    void stoppingTheChatbotSendsTheChatToTheTeam() throws Exception {
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = botChat(true);
            assertThat(call(HttpMethod.POST, chatPath(run) + "/chatbot/stop", manager, null).getStatusCode())
                    .isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(chatOf(run).getStatus()).isEqualTo(ChatStatus.OPEN);
            assertThat(endedRoutes(run)).hasSize(1);
        }
    }

    @Test
    void claimTakesItOver() throws Exception {
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = botChat(true);
            assertThat(call(HttpMethod.POST, chatPath(run) + "/claim", manager, null).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
            WaWebChat after = chatOf(run);
            assertThat(after.getStatus()).isEqualTo(ChatStatus.OPEN);
            assertThat(after.getAssignedUserId()).isEqualTo(manager.getId());
        }
    }

    @Test
    void aCampaignMaySendIntoABotChat() throws Exception {
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = botChat(true);
            sender.send(new CampaignActor("camp-1", "Festive"), brand.getId(), chatOf(run),
                    new SendRequest("Sale today", null, null), null).get(5, TimeUnit.SECONDS);
            assertThat(chatOf(run).getStatus()).isEqualTo(ChatStatus.BOT);
        }
    }
}
