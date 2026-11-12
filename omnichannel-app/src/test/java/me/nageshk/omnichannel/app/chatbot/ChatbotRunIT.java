package me.nageshk.omnichannel.app.chatbot;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import me.nageshk.omnichannel.app.automation.AutomationDevice;
import me.nageshk.omnichannel.db.chatbot.ChatbotRun;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunRepository;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunStatus;
import me.nageshk.omnichannel.db.chatbot.ChatbotStep;
import me.nageshk.omnichannel.db.chatbot.IfRunning;
import me.nageshk.omnichannel.db.chatbot.StartedBy;
import me.nageshk.omnichannel.db.chatbot.TaskKind;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactRepository;
import me.nageshk.omnichannel.db.contact.DoNotContact;
import me.nageshk.omnichannel.db.inbox.MessageDirection;
import me.nageshk.omnichannel.db.inbox.MessageSource;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.service.chatbot.node.TaskAnswer;
import me.nageshk.omnichannel.service.chatbot.run.ChatbotRunService;
import me.nageshk.omnichannel.service.chatbot.run.ChatbotRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/** Spec §5: a run walks the flow, waits for a WhatsApp reply, and is claimed exactly once per answer. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"omnichannel.rpc.timeout=2s", "omnichannel.chatbot.max-steps=7"})
class ChatbotRunIT extends ChatbotTestBase {

    static final String VEENU = "919799750743@c.us";

    @Autowired ChatbotRunService runs;
    @Autowired ChatbotRunner runner;
    @Autowired ChatbotRunRepository runRepo;
    @Autowired ContactRepository contacts;

    private Map<String, Object> askNameFlow() {
        return flow(List.of(
                node("n1", "send_message", "Welcome", textMessage("Hi {{contact.name}}")),
                node("n2", "ask_question", "AskName", Map.of("text", "What is your name?", "answerType", "TEXT")),
                node("n3", "send_message", "Done", textMessage("Thanks {{AskName.answer}}"))),
                List.of(edge("n1", null, "n2"), edge("n2", "next", "n3")));
    }

    private String publish(JsonNode bot) {
        assertThat(bots(HttpMethod.POST, "/" + bot.get("id").asString() + "/publish", manager, null).getStatusCode().is2xxSuccessful()).isTrue();
        return bot.get("id").asString();
    }

    private ChatbotRun run(String id) {
        return runRepo.findById(id).orElseThrow();
    }

    private Contact contactOf(WaWebChat chat) {
        return contacts.findById(chat.getContactId()).orElseThrow();
    }

    private ChatbotRunService.StartResult start(String chatbotId, WaWebChat chat, boolean test) throws Exception {
        ChatbotRunService.StartResult r = runs.start(brand.getId(), chatbotId, contactOf(chat), chat,
                StartedBy.test(manager.getId()), null, IfRunning.SKIP, test);
        if (r.started()) r.settled().get(5, TimeUnit.SECONDS);
        return r;
    }

    private static WaWebMessage inbound(WaWebChat chat, String body) {
        WaWebMessage m = new WaWebMessage();
        m.setId("in-" + System.nanoTime());
        m.setChatId(chat.getId());
        m.setWorkspaceId(chat.getWorkspaceId());
        m.setDirection(MessageDirection.IN);
        m.setSource(MessageSource.CONTACT);
        m.setType("text");
        m.setBody(body);
        m.setTimestamp(Instant.now());
        return m;
    }

    @Test
    void startSendsFirstMessageAndWaits() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(createChatbot("Lead qualifier", askNameFlow()));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRunService.StartResult r = start(id, chat, true);
            assertThat(r.started()).isTrue();
            assertThat(d.requests("sendMessage")).hasSize(2);
            assertThat(d.requests("sendMessage").get(0).params().get("content").asString()).isEqualTo("Hi Veenu");
            assertThat(d.requests("sendMessage").get(1).params().get("content").asString()).isEqualTo("What is your name?");
            ChatbotRun run = run(r.run().getId());
            assertThat(run.getStatus()).isEqualTo(ChatbotRunStatus.WAITING);
            assertThat(run.getCurrentNodeName()).isEqualTo("AskName");
            assertThat(run.getWaiting().kind()).isEqualTo(TaskKind.WHATSAPP_REPLY);
            assertThat(run.getWaiting().key()).isEqualTo(chat.getId());
            assertThat(run.getSteps()).hasSize(2);
            assertThat(run.getSteps().get(1).outcome()).isEqualTo(ChatbotStep.Outcome.WAITING);
            assertThat(run.getSteps().get(1).waitedFor().kind()).isEqualTo(TaskKind.WHATSAPP_REPLY);
            assertThat(chats.findById(chat.getId()).orElseThrow().getChatbot().runId()).isEqualTo(run.getId());
            assertThat(chats.findById(chat.getId()).orElseThrow().getChatbot().nodeName()).isEqualTo("AskName");
        }
    }

    @Test
    void replyAdvancesAndCompletes() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(createChatbot("Lead qualifier", askNameFlow()));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRunService.StartResult r = start(id, chat, true);
            // the customer answers through the inbox: a real inbound message event
            incoming(n1, VEENU, "chat", "Rahul", "Veenu");
            await(() -> run(r.run().getId()).getStatus() == ChatbotRunStatus.COMPLETED);
            ChatbotRun run = run(r.run().getId());
            assertThat(d.requests("sendMessage")).hasSize(3);
            assertThat(d.requests("sendMessage").get(2).params().get("content").asString()).isEqualTo("Thanks Rahul");
            assertThat(run.getSteps()).hasSize(3);
            assertThat(run.getSteps().get(1).outcome()).isEqualTo(ChatbotStep.Outcome.DONE);
            assertThat(run.getSteps().get(1).result()).containsEntry("answer", "Rahul");
            assertThat(run.getWaiting()).isNull();
            assertThat(run.getEndReason()).contains("Done");
            assertThat(chats.findById(chat.getId()).orElseThrow().getChatbot()).isNull();
        }
    }

    @Test
    void twoRepliesInOneInstantRunOneStep() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(createChatbot("Lead qualifier", askNameFlow()));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRunService.StartResult r = start(id, chat, true);
            WaWebMessage a = inbound(chat, "Rahul");
            WaWebMessage b = inbound(chat, "Rohit");
            CompletableFuture<Boolean> fa = CompletableFuture.supplyAsync(() -> runs.answer(TaskAnswer.whatsApp(chat.getId(), a)));
            CompletableFuture<Boolean> fb = CompletableFuture.supplyAsync(() -> runs.answer(TaskAnswer.whatsApp(chat.getId(), b)));
            boolean ra = fa.get(5, TimeUnit.SECONDS);
            boolean rb = fb.get(5, TimeUnit.SECONDS);
            assertThat(ra ^ rb).as("exactly one answer claims the run").isTrue();
            await(() -> run(r.run().getId()).getStatus() == ChatbotRunStatus.COMPLETED);
            assertThat(run(r.run().getId()).getSteps()).hasSize(3);
            assertThat(d.requests("sendMessage")).hasSize(3);
        }
    }

    @Test
    void answerForUnknownKeyIsDropped() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        assertThat(runs.answer(TaskAnswer.whatsApp("nochat", inbound(chat, "x")))).isFalse();
        assertThat(runRepo.count()).isZero();
    }

    @Test
    void skipWhenRunningReplaceWhenAsked() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(createChatbot("Lead qualifier", askNameFlow()));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRunService.StartResult first = start(id, chat, true);
            ChatbotRunService.StartResult skipped = runs.start(brand.getId(), id, contactOf(chat), chat,
                    StartedBy.test(manager.getId()), null, IfRunning.SKIP, true);
            assertThat(skipped.started()).isFalse();
            assertThat(skipped.reason()).contains("Lead qualifier");
            ChatbotRunService.StartResult replaced = runs.start(brand.getId(), id, contactOf(chat), chat,
                    StartedBy.test(manager.getId()), null, IfRunning.REPLACE, true);
            assertThat(replaced.started()).isTrue();
            replaced.settled().get(5, TimeUnit.SECONDS);
            assertThat(run(first.run().getId()).getStatus()).isEqualTo(ChatbotRunStatus.STOPPED);
            assertThat(run(first.run().getId()).getEndReason()).contains("replaced by");
            assertThat(run(replaced.run().getId()).getStatus()).isEqualTo(ChatbotRunStatus.WAITING);
            assertThat(chats.findById(chat.getId()).orElseThrow().getChatbot().runId()).isEqualTo(replaced.run().getId());
        }
    }

    @Test
    void startWithoutChatRunsUntilAMessagingNode() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        Contact c = contactOf(chat);
        chats.delete(chat);
        String id = publish(createChatbot("Bot", flow(List.of(
                node("n1", "update_contact", "SetName", Map.of("nameIfBlank", "Friend")),
                node("n2", "send_message", "Welcome", textMessage("Hi"))), List.of(edge("n1", null, "n2")))));
        ChatbotRunService.StartResult r = runs.start(brand.getId(), id, c, null, StartedBy.test(manager.getId()), null,
                IfRunning.SKIP, true);
        r.settled().get(5, TimeUnit.SECONDS);
        ChatbotRun run = run(r.run().getId());
        assertThat(run.getStatus()).isEqualTo(ChatbotRunStatus.FAILED);
        assertThat(run.getEndReason()).contains("no WhatsApp chat for this contact");
        assertThat(run.getSteps()).hasSize(2);
        assertThat(run.getSteps().get(0).outcome()).isEqualTo(ChatbotStep.Outcome.DONE);
        assertThat(run.getSteps().get(1).outcome()).isEqualTo(ChatbotStep.Outcome.FAILED);
    }

    @Test
    void runWithoutChatFindsTheContactsLatestChat() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(createChatbot("Bot", flow(List.of(node("n1", "send_message", "Welcome", textMessage("Hi"))), List.of())));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRunService.StartResult r = runs.start(brand.getId(), id, contactOf(chat), null,
                    StartedBy.test(manager.getId()), null, IfRunning.SKIP, true);
            r.settled().get(5, TimeUnit.SECONDS);
            assertThat(d.requests("sendMessage")).hasSize(1);
            ChatbotRun run = run(r.run().getId());
            assertThat(run.getChatId()).isEqualTo(chat.getId());
            assertThat(run.getPhoneNumberId()).isEqualTo(n1.getId());
            assertThat(run.getStatus()).isEqualTo(ChatbotRunStatus.COMPLETED);
        }
    }

    @Test
    void noEdgeEndsCompleted() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(createChatbot("Bot", flow(List.of(node("n1", "update_contact", "SetName", Map.of("nameIfBlank", "Friend"))), List.of())));
        ChatbotRunService.StartResult r = start(id, chat, true);
        ChatbotRun run = run(r.run().getId());
        assertThat(run.getStatus()).isEqualTo(ChatbotRunStatus.COMPLETED);
        assertThat(run.getEndReason()).isEqualTo("SetName: no edge from next");
        assertThat(run.getFinishedAt()).isNotNull();
    }

    @Test
    void stepCapFails() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(createChatbot("Loop", flow(List.of(
                node("s", "update_contact", "Start", Map.of("nameIfBlank", "s")),
                node("a", "update_contact", "A", Map.of("nameIfBlank", "x")),
                node("b", "update_contact", "B", Map.of("nameIfBlank", "y"))),
                List.of(edge("s", null, "a"), edge("a", null, "b"), edge("b", null, "a")))));
        ChatbotRunService.StartResult r = start(id, chat, true);
        ChatbotRun run = run(r.run().getId());
        assertThat(run.getStatus()).isEqualTo(ChatbotRunStatus.FAILED);
        assertThat(run.getEndReason()).isEqualTo("too many steps");
        assertThat(run.getSteps()).hasSize(7);
    }

    @Test
    void doNotContactStops() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        Contact c = contactOf(chat);
        c.setDoNotContact(new DoNotContact(Instant.now(), DoNotContact.Reason.MANUAL, null));
        contacts.save(c);
        String id = publish(createChatbot("Bot", askNameFlow()));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRunService.StartResult r = start(id, chat, true);
            assertThat(d.requests("sendMessage")).isEmpty();
            assertThat(run(r.run().getId()).getStatus()).isEqualTo(ChatbotRunStatus.STOPPED);
            assertThat(run(r.run().getId()).getEndReason()).isEqualTo("do not contact");
        }
    }

    @Test
    void chatbotMessagesCarrySourceAndName() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(createChatbot("Lead qualifier", askNameFlow()));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            start(id, chat, true);
            List<WaWebMessage> out = mongo.find(new Query(Criteria.where("chatId").is(chat.getId()).and("direction").is("OUT")),
                    WaWebMessage.class);
            assertThat(out).hasSize(2);
            assertThat(out.get(0).getSource()).isEqualTo(MessageSource.CHATBOT);
            assertThat(out.get(0).getSentByName()).isEqualTo("Chatbot: Lead qualifier");
            assertThat(out.get(0).getSentByUserId()).isNull();
            JsonNode thread = call(HttpMethod.GET, "/api/workspaces/" + brand.getId() + "/inbox/chats/" + chat.getId() + "/thread",
                    manager, null).getBody();
            boolean labelled = false;
            for (JsonNode item : thread.get("items")) {
                JsonNode m = item.get("message");
                if (m != null && !m.isNull() && m.get("direction").asString().equals("OUT")) {
                    assertThat(m.get("source").asString()).isEqualTo("CHATBOT");
                    assertThat(m.get("sentBy").get("name").asString()).isEqualTo("Chatbot: Lead qualifier");
                    labelled = true;
                }
            }
            assertThat(labelled).isTrue();
        }
    }

    @Test
    void sendRetriedWhenDeviceOffline() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(createChatbot("Bot", flow(List.of(node("n1", "send_message", "Welcome", textMessage("Hi"))), List.of())));
        ChatbotRunService.StartResult r = start(id, chat, true);
        ChatbotRun waiting = run(r.run().getId());
        assertThat(waiting.getStatus()).isEqualTo(ChatbotRunStatus.RUNNING);
        assertThat(waiting.getSendAttempts()).isEqualTo(1);
        assertThat(waiting.getRetryAt()).isNotNull();
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            clock.advance(java.time.Duration.ofSeconds(31));
            runner.retryDue().get(5, TimeUnit.SECONDS);
            assertThat(d.requests("sendMessage")).hasSize(1);
            assertThat(run(r.run().getId()).getStatus()).isEqualTo(ChatbotRunStatus.COMPLETED);
        }
    }
}
