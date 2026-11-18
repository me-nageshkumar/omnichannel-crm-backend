package me.nageshk.omnichannel.app.chatbot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import me.nageshk.omnichannel.app.automation.AutomationDevice;
import me.nageshk.omnichannel.crm.deal.CrmDeal;
import me.nageshk.omnichannel.db.chatbot.ChatbotRun;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunRepository;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunStatus;
import me.nageshk.omnichannel.db.chatbot.IfRunning;
import me.nageshk.omnichannel.db.chatbot.StartedBy;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactRepository;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatAction;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.service.chatbot.run.ChatbotRunService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/** Spec §4.2: Send template, Assign chat and Add to CRM nodes act as the brand, like the automation actions. */
class ChatbotActionsNodesIT extends ChatbotTestBase {

    static final String VEENU = "919799750743@c.us";

    @Autowired ChatbotRunService runs;
    @Autowired ChatbotRunRepository runRepo;
    @Autowired ContactRepository contacts;

    private String publish(JsonNode bot) {
        assertThat(bots(HttpMethod.POST, "/" + bot.get("id").asString() + "/publish", manager, null).getStatusCode().is2xxSuccessful()).isTrue();
        return bot.get("id").asString();
    }

    private ChatbotRun start(String chatbotId, WaWebChat chat) throws Exception {
        Contact c = contacts.findById(chat.getContactId()).orElseThrow();
        ChatbotRunService.StartResult r = runs.start(brand.getId(), chatbotId, c, chat, StartedBy.test(manager.getId()),
                null, IfRunning.SKIP, true);
        assertThat(r.started()).isTrue();
        r.settled().get(5, TimeUnit.SECONDS);
        return runRepo.findById(r.run().getId()).orElseThrow();
    }

    private ChatbotRun run(String id) {
        return runRepo.findById(id).orElseThrow();
    }

    @Test
    void sendTemplateRendersValuesFromVariables() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String t = template("Order noted", "Order {{order_id}} noted for {{contact.name}}");
        Map<String, Object> f = flow(List.of(
                node("q", "ask_question", "AskOrder", Map.of("text", "Order number?", "answerType", "TEXT")),
                node("t", "send_template", "Confirm", Map.of("templateId", t, "values", Map.of("order_id", "{{AskOrder.answer}}")))),
                List.of(edge("q", "next", "t")));
        String id = publish(createChatbot("Bot", f));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            incoming(n1, VEENU, "chat", "A-77", "Veenu");
            await(() -> run(run.getId()).getStatus() == ChatbotRunStatus.COMPLETED);
            assertThat(d.requests("sendMessage").get(1).params().get("content").asString()).isEqualTo("Order A-77 noted for Veenu");
            List<WaWebMessage> out = mongo.find(new Query(Criteria.where("chatId").is(chat.getId()).and("direction").is("OUT")), WaWebMessage.class);
            WaWebMessage sent = out.stream().filter(m -> "Order A-77 noted for Veenu".equals(m.getBody())).findFirst().orElseThrow();
            assertThat(sent.getTemplateId()).isEqualTo(t);
            assertThat(sent.getSentByName()).isEqualTo("Chatbot: Bot");
        }
    }

    @Test
    void assignChatToTeamQueue() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        Map<String, Object> f = flow(List.of(node("a", "assign_chat", "Hand", Map.of("mode", "TEAM_QUEUE", "teamId", support.getId()))), List.of());
        String id = publish(createChatbot("Bot", f));
        ChatbotRun run = start(id, chat);
        assertThat(run.getStatus()).isEqualTo(ChatbotRunStatus.HANDED_OVER);
        WaWebChat after = chats.findById(chat.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(me.nageshk.omnichannel.db.inbox.ChatStatus.OPEN);
        assertThat(after.getAssignedTeamId()).isEqualTo(support.getId());
        assertThat(after.getAssignedUserId()).isNull();
        assertThat(((Map<?, ?>) run.getSteps().get(0).result().get("assignedTo")).get("teamId")).isEqualTo(support.getId());
        List<WaWebChatAction> actions = mongo.find(new Query(Criteria.where("chatId").is(chat.getId())), WaWebChatAction.class);
        assertThat(actions).anySatisfy(a -> assertThat(a.getByName()).isEqualTo("Chatbot: Bot"));
    }

    @Test
    void assignRightAfterASendInTheSameRun() throws Exception {
        // the send updates the chat in the database; the assignment must not save the run's older copy over it
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        Map<String, Object> f = flow(List.of(
                node("s", "send_message", "Pay", textMessage("Payment details follow")),
                node("a", "assign_chat", "Hand", Map.of("mode", "TEAM_ROUND_ROBIN", "teamId", support.getId()))),
                List.of(edge("s", "next", "a")));
        String id = publish(createChatbot("Bot", f));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            await(() -> !run(run.getId()).getStatus().active());
            ChatbotRun done = run(run.getId());
            assertThat(done.getStatus()).as(done.getEndReason()).isEqualTo(ChatbotRunStatus.HANDED_OVER);
            WaWebChat after = chats.findById(chat.getId()).orElseThrow();
            assertThat(after.getAssignedUserId()).isIn(agent.getId(), agent2.getId());
            assertThat(after.getLastMessageAt()).isNotNull();
        }
    }

    @Test
    void assignRoundRobin() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        Map<String, Object> f = flow(List.of(node("a", "assign_chat", "Hand", Map.of("mode", "TEAM_ROUND_ROBIN", "teamId", support.getId()))), List.of());
        String id = publish(createChatbot("Bot", f));
        ChatbotRun run = start(id, chat);
        WaWebChat after = chats.findById(chat.getId()).orElseThrow();
        assertThat(after.getAssignedUserId()).isIn(agent.getId(), agent2.getId());
        assertThat(((Map<?, ?>) run.getSteps().get(0).result().get("assignedTo")).get("userId")).isEqualTo(after.getAssignedUserId());
    }

    private static Map<String, Object> handOver() {
        return flow(List.of(node("a", "assign_chat", "Hand", Map.of("mode", "NUMBER_ROUTING"))), List.of());
    }

    @Test
    void handOverByNumberRoutingEndsTheRunAndRoutes() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        ChatbotRun run = start(publish(createChatbot("Bot", handOver())), chat);

        assertThat(run.getStatus()).isEqualTo(ChatbotRunStatus.HANDED_OVER);
        WaWebChat after = chats.findById(chat.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(me.nageshk.omnichannel.db.inbox.ChatStatus.OPEN);
        assertThat(after.getAssignedTeamId()).isEqualTo(support.getId());
        assertThat(after.getHandedOverAt()).isNotNull();
        List<WaWebChatAction> actions = mongo.find(new Query(Criteria.where("chatId").is(chat.getId())), WaWebChatAction.class);
        assertThat(actions).anySatisfy(a -> {
            assertThat(a.getReason()).isEqualTo(me.nageshk.omnichannel.db.inbox.RoutedReason.CHATBOT_HANDOVER);
            assertThat(a.getByName()).isEqualTo("Chatbot: Bot");
        });
    }

    @Test
    void handOverKeepsAnAgentWhoHoldsTheChat() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        WaWebChat held = chats.findById(chat.getId()).orElseThrow();
        held.setAssignedUserId(agent.getId());
        chats.save(held);

        ChatbotRun run = start(publish(createChatbot("Bot", handOver())), chat);

        assertThat(run.getStatus()).isEqualTo(ChatbotRunStatus.HANDED_OVER);
        assertThat(run.getSteps().get(0).summary()).startsWith("Stays with ");
        assertThat(chats.findById(chat.getId()).orElseThrow().getAssignedUserId()).isEqualTo(agent.getId());
    }

    @Test
    void handOverWithNoTeamLeavesItOpen() throws Exception {
        WaWebChat chat = incoming(n2, VEENU, "chat", "hello", "Veenu");

        ChatbotRun run = start(publish(createChatbot("Bot", handOver())), chat);

        assertThat(run.getStatus()).isEqualTo(ChatbotRunStatus.HANDED_OVER);
        assertThat(run.getSteps().get(0).summary()).isEqualTo("Open, waiting for the team");
        WaWebChat after = chats.findById(chat.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(me.nageshk.omnichannel.db.inbox.ChatStatus.OPEN);
        assertThat(after.getAssignedUserId()).isNull();
    }

    @Test
    void nothingRunsAfterAHandOver() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        // a flow saved before the node ended the chatbot: the edge is still there, and it still publishes
        Map<String, Object> f = flow(List.of(
                node("a", "assign_chat", "Hand", Map.of("mode", "TEAM_QUEUE", "teamId", support.getId())),
                node("s", "send_message", "After", textMessage("Nobody should get this"))),
                List.of(edge("a", "next", "s")));
        String id = publish(createChatbot("Bot", f));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            assertThat(run.getStatus()).isEqualTo(ChatbotRunStatus.HANDED_OVER);
            Thread.sleep(200);
            assertThat(d.requests("sendMessage")).isEmpty();
        }
    }

    @Test
    void assignToAgentWhoCannotSeeTheNumberFails() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        Map<String, Object> f = flow(List.of(node("a", "assign_chat", "Hand", Map.of("mode", "AGENT", "userId", outsider.getId()))), List.of());
        String id = publish(createChatbot("Bot", f));
        ChatbotRun run = start(id, chat);
        assertThat(run.getStatus()).isEqualTo(ChatbotRunStatus.FAILED);
        assertThat(run.getEndReason()).contains("cannot see this number");
    }

    @Test
    void addToCrmCreatesDealOnce() throws Exception {
        enableCrm();
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        Map<String, Object> f = flow(List.of(node("c", "add_to_crm", "Lead", Map.of("dealTitle", "{{name}} – WhatsApp"))), List.of());
        String id = publish(createChatbot("Bot", f));
        ChatbotRun first = start(id, chat);
        assertThat(first.getStatus()).isEqualTo(ChatbotRunStatus.COMPLETED);
        assertThat(first.getSteps().get(0).result()).containsEntry("created", true);
        assertThat(first.getSteps().get(0).result().get("dealId")).isNotNull();
        List<CrmDeal> deals = mongo.findAll(CrmDeal.class);
        assertThat(deals).hasSize(1);
        assertThat(deals.get(0).getTitle()).isEqualTo("Veenu – WhatsApp");
        ChatbotRun second = start(id, chat);
        assertThat(second.getSteps().get(0).result()).containsEntry("created", false);
        assertThat(mongo.findAll(CrmDeal.class)).hasSize(1);
    }

    @Test
    void addToCrmWhenCrmOffFails() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        Map<String, Object> f = flow(List.of(node("c", "add_to_crm", "Lead", Map.of())), List.of());
        // publishing refuses it (CRM off); a test run of the draft shows the runtime failure
        JsonNode bot = createChatbot("Bot", f);
        Contact c = contacts.findById(chat.getContactId()).orElseThrow();
        ChatbotRunService.StartResult r = runs.start(brand.getId(), bot.get("id").asString(), c, chat,
                StartedBy.test(manager.getId()), null, IfRunning.SKIP, true);
        r.settled().get(5, TimeUnit.SECONDS);
        ChatbotRun run = run(r.run().getId());
        assertThat(run.getStatus()).isEqualTo(ChatbotRunStatus.FAILED);
        assertThat(run.getEndReason()).containsIgnoringCase("CRM");
    }
}
