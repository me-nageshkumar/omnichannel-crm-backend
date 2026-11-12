package me.nageshk.omnichannel.app.chatbot;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.app.automation.AutomationDevice;
import me.nageshk.omnichannel.db.automation.AutomationAction;
import me.nageshk.omnichannel.db.automation.AutomationActionType;
import me.nageshk.omnichannel.db.automation.AutomationEventKind;
import me.nageshk.omnichannel.db.automation.AutomationEventRecord;
import me.nageshk.omnichannel.db.automation.AutomationRule;
import me.nageshk.omnichannel.db.automation.AutomationRun;
import me.nageshk.omnichannel.db.automation.EventData;
import me.nageshk.omnichannel.db.automation.RuleStatus;
import me.nageshk.omnichannel.db.automation.StepOutcome;
import me.nageshk.omnichannel.db.chatbot.ChatbotRun;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunRepository;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunStatus;
import me.nageshk.omnichannel.db.chatbot.IfRunning;
import me.nageshk.omnichannel.db.chatbot.StartedBy;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactRepository;
import me.nageshk.omnichannel.db.contact.DoNotContact;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.service.automation.ActionContext;
import me.nageshk.omnichannel.service.automation.ActionResult;
import me.nageshk.omnichannel.service.automation.AutomationWorker;
import me.nageshk.omnichannel.service.automation.StartChatbotHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** Spec §3.1, §3.4: the Start chatbot action and the captured "chatbot is running" fact. */
class StartChatbotActionIT extends ChatbotTestBase {

    static final String VEENU = "919799750743@c.us";

    @Autowired AutomationWorker worker;
    @Autowired StartChatbotHandler handler;
    @Autowired ChatbotRunRepository runRepo;
    @Autowired ContactRepository contacts;

    private Map<String, Object> askFlow() {
        return flow(List.of(
                node("q", "ask_question", "AskName", Map.of("text", "Name?", "answerType", "TEXT")),
                node("m", "send_message", "Done", textMessage("Thanks {{AskName.answer}}"))), List.of(edge("q", "next", "m")));
    }

    private String publish(JsonNode bot) {
        assertThat(bots(HttpMethod.POST, "/" + bot.get("id").asString() + "/publish", manager, null).getStatusCode().is2xxSuccessful()).isTrue();
        return bot.get("id").asString();
    }

    private static AutomationAction startAction(String chatbotId, IfRunning ifRunning) {
        return new AutomationAction(AutomationActionType.START_CHATBOT, null, null, null, null, null, null, null, null, null,
                chatbotId, ifRunning);
    }

    private ActionContext ctx(WaWebChat chat, Contact contact, String eventId) {
        EventData.ChatData c = chat == null ? null : new EventData.ChatData(chat.getId(), false, chat.getStatus(), null, null, false);
        EventData.ContactData k = contact == null ? null
                : new EventData.ContactData(contact.getId(), contact.getName(), contact.getPhone(), false, contact.getDoNotContact() != null);
        return new ActionContext(brand.getId(), acme.getId(), AutomationEventKind.MESSAGE_RECEIVED, eventId,
                new EventData(c, null, null, k, null, null, null, null), "r1", "Welcome rule", false);
    }

    private Contact contactOf(WaWebChat chat) {
        return contacts.findById(chat.getContactId()).orElseThrow();
    }

    private List<ChatbotRun> runs() {
        return runRepo.findAll();
    }

    @Test
    void publishedRuleStartsChatbotOnMessage() throws Exception {
        String id = publish(createChatbot("Lead qualifier", askFlow()));
        rule("Welcome", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.PUBLISHED, 1, null, startAction(id, null));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
            assertThat(worker.tick()).isEqualTo(1);
            await(() -> runs().size() == 1 && runs().get(0).getStatus() == ChatbotRunStatus.WAITING);
            ChatbotRun run = runs().get(0);
            assertThat(run.getChatId()).isEqualTo(chat.getId());
            assertThat(run.getStartedBy().ruleName()).isEqualTo("Welcome");
            assertThat(run.getStartedBy().eventId()).isNotNull();
            assertThat(run.isTest()).isFalse();
            assertThat(((Map<?, ?>) ((Map<?, ?>) run.getVariables().get("event")).get("message")).get("text")).isEqualTo("hello");
            AutomationRun ruleRun = mongo.findAll(AutomationRun.class).get(0);
            assertThat(ruleRun.getSteps().get(0).outcome()).isEqualTo(StepOutcome.DONE);
            assertThat(ruleRun.getSteps().get(0).reason()).startsWith("Started “Lead qualifier”: waiting at AskName");
            assertThat(d.requests("sendMessage")).hasSize(1);
        }
    }

    @Test
    void secondEventWhileRunningIsSkippedAndTheReplyAdvancesTheRun() throws Exception {
        String id = publish(createChatbot("Lead qualifier", askFlow()));
        rule("Welcome", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.PUBLISHED, 1, null, startAction(id, null));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            incoming(n1, VEENU, "chat", "hello", "Veenu");
            worker.tick();
            await(() -> runs().size() == 1 && runs().get(0).getStatus() == ChatbotRunStatus.WAITING);
            incoming(n1, VEENU, "chat", "Rahul", "Veenu");
            await(() -> runs().get(0).getStatus() == ChatbotRunStatus.COMPLETED);
            // the reply's own event: by then the run may have ended, but the fact was captured at write time
            assertThat(worker.tick()).isEqualTo(1);
            List<AutomationRun> ruleRuns = mongo.findAll(AutomationRun.class);
            assertThat(ruleRuns).hasSize(2);
            List<AutomationEventRecord> history = mongo.findAll(AutomationEventRecord.class);
            assertThat(history).hasSize(2);
            assertThat(history.stream().map(h -> h.getData().chat().chatbotRunning()).toList()).containsExactlyInAnyOrder(false, true);
        }
    }

    @Test
    void chatbotRunningConditionIsCaptured() throws Exception {
        String id = publish(createChatbot("Lead qualifier", askFlow()));
        rule("Welcome", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.PUBLISHED, 1,
                leaf("chat.chatbotRunning", "is", false), startAction(id, null));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            incoming(n1, VEENU, "chat", "hello", "Veenu");
            worker.tick();
            await(() -> runs().size() == 1 && runs().get(0).getStatus() == ChatbotRunStatus.WAITING);
            // the customer's answer arrives while the run waits: the rule must not match it
            incoming(n1, VEENU, "chat", "Rahul", "Veenu");
            await(() -> runs().get(0).getStatus() == ChatbotRunStatus.COMPLETED);
            worker.tick();
            assertThat(mongo.findAll(AutomationRun.class)).hasSize(1);
            assertThat(runs()).hasSize(1);
        }
    }

    @Test
    void reclaimedEventFindsExistingRun() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(createChatbot("Lead qualifier", askFlow()));
        ChatbotRun existing = new ChatbotRun();
        existing.setCustomerId(acme.getId());
        existing.setWorkspaceId(brand.getId());
        existing.setChatbotId(id);
        existing.setChatbotName("Lead qualifier");
        existing.setContactId(chat.getContactId());
        existing.setChatId(chat.getId());
        existing.setStatus(ChatbotRunStatus.WAITING);
        existing.setCurrentNodeName("AskName");
        existing.setStartedBy(new StartedBy("r1", "Welcome rule", "m-77", null));
        existing.setCreatedAt(Instant.now());
        runRepo.insert(existing);
        ActionResult r = handler.run(ctx(chat, contactOf(chat), "m-77"), startAction(id, null));
        assertThat(r.outcome()).isEqualTo(StepOutcome.DONE);
        assertThat(r.reason()).isEqualTo("Started “Lead qualifier”: waiting at AskName");
        assertThat(runRepo.count()).isEqualTo(1);
    }

    @Test
    void moveToDraftKeepsRunningRunsAndRefusesNewStarts() throws Exception {
        String id = publish(createChatbot("Lead qualifier", askFlow()));
        rule("Welcome", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.PUBLISHED, 1, null, startAction(id, null));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            incoming(n1, VEENU, "chat", "hello", "Veenu");
            worker.tick();
            await(() -> runs().size() == 1 && runs().get(0).getStatus() == ChatbotRunStatus.WAITING);
            bots(HttpMethod.POST, "/" + id + "/unpublish", manager, null);
            // the waiting run still finishes on its version
            incoming(n1, VEENU, "chat", "Rahul", "Veenu");
            await(() -> runs().get(0).getStatus() == ChatbotRunStatus.COMPLETED);
            assertThat(d.requests("sendMessage").get(1).params().get("content").asString()).isEqualTo("Thanks Rahul");
            // a new start fails
            WaWebChat other = incoming(n1, "919800000002@c.us", "chat", "hi", "Other");
            worker.tick();
            worker.tick();
            List<AutomationRun> ruleRuns = mongo.findAll(AutomationRun.class);
            AutomationRun last = ruleRuns.stream().filter(x -> other.getContactId().equals(x.getContactId())).findFirst().orElseThrow();
            assertThat(last.getSteps().get(0).outcome()).isEqualTo(StepOutcome.FAILED);
            assertThat(last.getSteps().get(0).reason()).contains("is not published");
            assertThat(runs()).hasSize(1);
        }
    }

    @Test
    void publishRefusesDraftChatbot() {
        JsonNode draft = createChatbot("Draft bot", askFlow());
        AutomationRule r = rule("Welcome", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.DRAFT, 1, null,
                startAction(draft.get("id").asString(), null));
        ResponseEntity<JsonNode> res = api(HttpMethod.POST, "/rules/" + r.getId() + "/publish", manager, null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("fields").get("actions[0].chatbotId").asString()).contains("draft");
        AutomationRule none = rule("Nobody", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.DRAFT, 1, null, startAction(null, null));
        ResponseEntity<JsonNode> res2 = api(HttpMethod.POST, "/rules/" + none.getId() + "/publish", manager, null);
        assertThat(res2.getBody().get("fields").has("actions[0].chatbotId")).isTrue();
    }

    @Test
    void doNotContactSkipped() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        Contact c = contactOf(chat);
        c.setDoNotContact(new DoNotContact(Instant.now(), DoNotContact.Reason.MANUAL, null));
        contacts.save(c);
        String id = publish(createChatbot("Lead qualifier", askFlow()));
        ActionResult r = handler.run(ctx(chat, contactOf(chat), "m-1"), startAction(id, null));
        assertThat(r.outcome()).isEqualTo(StepOutcome.SKIPPED);
        assertThat(runRepo.count()).isZero();
    }

    @Test
    void replaceStopsTheOldRun() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String first = publish(createChatbot("First", askFlow()));
        String second = publish(createChatbot("Second", askFlow()));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ActionResult a = handler.run(ctx(chat, contactOf(chat), "m-1"), startAction(first, null));
            assertThat(a.outcome()).isEqualTo(StepOutcome.DONE);
            ActionResult skipped = handler.run(ctx(chat, contactOf(chat), "m-2"), startAction(second, IfRunning.SKIP));
            assertThat(skipped.outcome()).isEqualTo(StepOutcome.SKIPPED);
            assertThat(skipped.reason()).contains("First");
            ActionResult replaced = handler.run(ctx(chat, contactOf(chat), "m-3"), startAction(second, IfRunning.REPLACE));
            assertThat(replaced.outcome()).isEqualTo(StepOutcome.DONE);
            assertThat(runs()).hasSize(2);
            assertThat(runs().stream().filter(r -> r.getChatbotId().equals(first)).findFirst().orElseThrow().getStatus())
                    .isEqualTo(ChatbotRunStatus.STOPPED);
        }
    }

    @Test
    void dealWithoutChatStartsRun() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        Contact c = contactOf(chat);
        chats.delete(chat);
        String id = publish(createChatbot("Bot", flow(List.of(node("u", "update_contact", "Tag", Map.of("nameIfBlank", "Friend"))), List.of())));
        EventData.ContactData k = new EventData.ContactData(c.getId(), c.getName(), c.getPhone(), true, false);
        ActionContext ctx = new ActionContext(brand.getId(), acme.getId(), AutomationEventKind.DEAL_LANE_CHANGED, "d-1",
                new EventData(null, null, null, k, null, null, null, null), "r1", "Lane rule", false);
        ActionResult r = handler.run(ctx, startAction(id, null));
        assertThat(r.outcome()).isEqualTo(StepOutcome.DONE);
        assertThat(r.reason()).isEqualTo("Started “Bot”: completed");
        assertThat(runs()).singleElement().satisfies(run -> {
            assertThat(run.getChatId()).isNull();
            assertThat(run.getStatus()).isEqualTo(ChatbotRunStatus.COMPLETED);
        });
    }

    @Test
    void optionsListChatbots() {
        createChatbot("Bot", askFlow());
        JsonNode o = api(HttpMethod.GET, "/options", manager, null).getBody();
        assertThat(o.get("chatbots")).hasSize(1);
        assertThat(o.get("chatbots").get(0).get("name").asString()).isEqualTo("Bot");
        assertThat(o.get("chatbots").get(0).get("status").asString()).isEqualTo("DRAFT");
    }
}
