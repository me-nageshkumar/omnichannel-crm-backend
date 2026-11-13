package me.nageshk.omnichannel.app.chatbot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import me.nageshk.omnichannel.app.automation.AutomationDevice;
import me.nageshk.omnichannel.db.chatbot.ChatbotRun;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunRepository;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunStatus;
import me.nageshk.omnichannel.db.chatbot.IfRunning;
import me.nageshk.omnichannel.db.chatbot.StartedBy;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactRepository;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.service.chatbot.run.ChatbotRunService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** Spec §8.1 Runs: the runs list, a run's detail with steps and variables, and stopping a run. */
class ChatbotRunsApiIT extends ChatbotTestBase {

    static final String VEENU = "919799750743@c.us";

    @Autowired ChatbotRunService runs;
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

    private ChatbotRun start(String chatbotId, WaWebChat chat) throws Exception {
        Contact c = contacts.findById(chat.getContactId()).orElseThrow();
        ChatbotRunService.StartResult r = runs.start(brand.getId(), chatbotId, c, chat, StartedBy.test(manager.getId()),
                null, IfRunning.SKIP, true);
        r.settled().get(5, TimeUnit.SECONDS);
        return runRepo.findById(r.run().getId()).orElseThrow();
    }

    @Test
    void listsRunsNewestFirstWithStatusFilter() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(createChatbot("Bot", askFlow()));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun first = start(id, chat);
            incoming(n1, VEENU, "chat", "Rahul", "Veenu");
            await(() -> runRepo.findById(first.getId()).orElseThrow().getStatus() == ChatbotRunStatus.COMPLETED);
            clock.advance(java.time.Duration.ofMinutes(1));
            ChatbotRun second = start(id, chat);
            JsonNode page = bots(HttpMethod.GET, "/" + id + "/runs", manager, null).getBody();
            assertThat(page.get("total").asInt()).isEqualTo(2);
            assertThat(page.get("items").get(0).get("id").asString()).isEqualTo(second.getId());
            assertThat(page.get("items").get(0).get("status").asString()).isEqualTo("WAITING");
            assertThat(page.get("items").get(0).get("waiting").get("kind").asString()).isEqualTo("WHATSAPP_REPLY");
            assertThat(page.get("items").get(0).get("contact").get("name").asString()).isEqualTo("Veenu");
            assertThat(page.get("items").get(0).get("startedBy").get("ruleName").asString()).isEqualTo("Test");
            JsonNode done = bots(HttpMethod.GET, "/" + id + "/runs?status=COMPLETED", manager, null).getBody();
            assertThat(done.get("total").asInt()).isEqualTo(1);
            assertThat(done.get("items").get(0).get("id").asString()).isEqualTo(first.getId());
        }
    }

    @Test
    void runDetailHasStepsAndVariables() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(createChatbot("Bot", askFlow()));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            incoming(n1, VEENU, "chat", "Rahul", "Veenu");
            await(() -> runRepo.findById(run.getId()).orElseThrow().getStatus() == ChatbotRunStatus.COMPLETED);
            JsonNode view = bots(HttpMethod.GET, "/" + id + "/runs/" + run.getId(), manager, null).getBody();
            assertThat(view.get("steps")).hasSize(2);
            assertThat(view.get("steps").get(0).get("nodeName").asString()).isEqualTo("AskName");
            assertThat(view.get("steps").get(0).get("outcome").asString()).isEqualTo("DONE");
            assertThat(view.get("steps").get(0).get("waitedFor").get("kind").asString()).isEqualTo("WHATSAPP_REPLY");
            assertThat(view.get("steps").get(0).get("result").get("answer").asString()).isEqualTo("Rahul");
            assertThat(view.get("variables").get("AskName").get("answer").asString()).isEqualTo("Rahul");
            assertThat(view.get("variables").get("contact").get("name").asString()).isEqualTo("Veenu");
            assertThat(view.get("endReason").asString()).contains("Done");
            assertThat(view.get("chat").get("remoteId").asString()).isEqualTo(VEENU);
            assertThat(view.get("number").get("id").asString()).isEqualTo(n1.getId());
        }
    }

    @Test
    void managerStopsRun() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(createChatbot("Bot", askFlow()));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            ResponseEntity<JsonNode> stop = bots(HttpMethod.POST, "/" + id + "/runs/" + run.getId() + "/stop", manager, null);
            assertThat(stop.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            ChatbotRun after = runRepo.findById(run.getId()).orElseThrow();
            assertThat(after.getStatus()).isEqualTo(ChatbotRunStatus.STOPPED);
            assertThat(after.getStoppedBy()).isEqualTo(manager.getId());
            JsonNode view = bots(HttpMethod.GET, "/" + id + "/runs/" + run.getId(), manager, null).getBody();
            assertThat(view.get("stoppedBy").get("name").asString()).isNotBlank();
            assertThat(bots(HttpMethod.POST, "/" + id + "/runs/" + run.getId() + "/stop", manager, null).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT);
        }
    }

    @Test
    void agentCannotListOrStop() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(createChatbot("Bot", askFlow()));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            assertThat(bots(HttpMethod.GET, "/" + id + "/runs", agent, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(bots(HttpMethod.POST, "/" + id + "/runs/" + run.getId() + "/stop", agent, null).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void otherBrand404() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(createChatbot("Bot", askFlow()));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            ResponseEntity<JsonNode> r = call(HttpMethod.GET, "/api/workspaces/" + other.getId() + "/chatbots/" + id + "/runs/" + run.getId(),
                    admin, null);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
