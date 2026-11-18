package me.nageshk.omnichannel.app.chatbot;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import me.nageshk.omnichannel.app.automation.AutomationDevice;
import me.nageshk.omnichannel.app.support.TestBilling;
import me.nageshk.omnichannel.db.chatbot.ChatbotRun;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunRepository;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunStatus;
import me.nageshk.omnichannel.db.chatbot.IfRunning;
import me.nageshk.omnichannel.db.chatbot.StartedBy;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactRepository;
import me.nageshk.omnichannel.db.inbox.MessageDirection;
import me.nageshk.omnichannel.db.inbox.MessageSource;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.service.chatbot.node.TaskAnswer;
import me.nageshk.omnichannel.service.chatbot.run.ChatbotRunService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** A chatbot's run ends at the message the wallet cannot pay for. */
class ChatbotWalletIT extends ChatbotTestBase {

    static final String VEENU = "919799750743@c.us";

    @Autowired ChatbotRunService runs;
    @Autowired ChatbotRunRepository runRepo;
    @Autowired ContactRepository contacts;
    @Autowired TestBilling billing;

    @BeforeEach
    void noPlan() {
        billing.reset();
    }

    @AfterEach
    void leaveNoPlan() {
        billing.reset();
    }

    private Map<String, Object> askFlow() {
        return flow(List.of(
                node("q", "ask_question", "AskName", Map.of("text", "Name?", "answerType", "TEXT")),
                node("m", "send_message", "Done", textMessage("Thanks"))), List.of(edge("q", "next", "m")));
    }

    private String publish(JsonNode bot) {
        ResponseEntity<JsonNode> res = bots(HttpMethod.POST, "/" + bot.get("id").asString() + "/publish", manager, null);
        assertThat(res.getStatusCode().is2xxSuccessful()).as(String.valueOf(res.getBody())).isTrue();
        return bot.get("id").asString();
    }

    private ChatbotRunService.StartResult start(String chatbotId, WaWebChat chat) {
        Contact c = contacts.findById(chat.getContactId()).orElseThrow();
        return runs.start(brand.getId(), chatbotId, c, chat, StartedBy.test(manager.getId()), null, IfRunning.SKIP, true);
    }

    private ChatbotRun run(String id) {
        return runRepo.findById(id).orElseThrow();
    }

    private WaWebMessage reply(WaWebChat chat, String body) {
        WaWebMessage m = new WaWebMessage();
        m.setId("in-" + body);
        m.setChatId(chat.getId());
        m.setWorkspaceId(brand.getId());
        m.setDirection(MessageDirection.IN);
        m.setSource(MessageSource.CONTACT);
        m.setType("chat");
        m.setBody(body);
        m.setTimestamp(Instant.now());
        return m;
    }

    @Test
    void aRunEndsAtTheMessageTheWalletCannotPayFor() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(createChatbot("Lead qualifier", askFlow()));
        billing.assign(acme.getId(), Set.of("chatbots"), Map.of());
        billing.messageRate(acme.getId(), "0.25");
        billing.wallet(acme.getId(), "0.2500");
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRunService.StartResult r = start(id, chat);
            assertThat(r.started()).isTrue();
            r.settled().get(5, TimeUnit.SECONDS);
            assertThat(run(r.run().getId()).getStatus()).isEqualTo(ChatbotRunStatus.WAITING);
            assertThat(d.requests("sendMessage")).hasSize(1);

            assertThat(runs.answer(TaskAnswer.whatsApp(chat.getId(), reply(chat, "Rahul")))).isTrue();
            await(() -> !run(r.run().getId()).getStatus().active());

            ChatbotRun ended = run(r.run().getId());
            assertThat(ended.getStatus()).isEqualTo(ChatbotRunStatus.FAILED);
            assertThat(ended.getEndReason()).isEqualTo("the wallet balance is too low");
            assertThat(d.requests("sendMessage")).hasSize(1);
        }
    }
}
