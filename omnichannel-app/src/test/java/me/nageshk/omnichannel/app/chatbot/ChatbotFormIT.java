package me.nageshk.omnichannel.app.chatbot;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import me.nageshk.omnichannel.app.automation.AutomationDevice;
import me.nageshk.omnichannel.db.chatbot.ChatbotForm;
import me.nageshk.omnichannel.db.chatbot.ChatbotFormRepository;
import me.nageshk.omnichannel.db.chatbot.ChatbotRun;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunRepository;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunStatus;
import me.nageshk.omnichannel.db.chatbot.IfRunning;
import me.nageshk.omnichannel.db.chatbot.StartedBy;
import me.nageshk.omnichannel.db.chatbot.TaskKind;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactRepository;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.service.chatbot.run.ChatbotRunService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;

/** Spec §4.2 Ask with a form, §8.4: the form page is the second task kind. */
class ChatbotFormIT extends ChatbotTestBase {

    static final String VEENU = "919799750743@c.us";

    @Autowired ChatbotRunService runs;
    @Autowired ChatbotRunRepository runRepo;
    @Autowired ChatbotFormRepository forms;
    @Autowired ContactRepository contacts;

    private Map<String, Object> formFlow() {
        Map<String, Object> ask = Map.of(
                "title", "Your details",
                "intro", "Two quick questions, {{contact.name}}",
                "fields", List.of(
                        Map.of("key", "city", "label", "City", "type", "TEXT", "required", true),
                        Map.of("key", "budget", "label", "Budget", "type", "NUMBER", "required", false)),
                "submitText", "Send",
                "thanksText", "Got it, thanks!",
                "linkMessage", "Please fill this in: {{form.url}}");
        return flow(List.of(
                node("f", "ask_form", "AskDetails", ask),
                node("m", "send_message", "Done", textMessage("{{AskDetails.form.city}} / {{AskDetails.form.budget}}"))),
                List.of(edge("f", "next", "m")));
    }

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

    private ResponseEntity<String> getForm(String token) {
        return rest.getForEntity(url("/f/" + token), String.class);
    }

    private ResponseEntity<String> submit(String token, Map<String, String> values) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        values.forEach(body::add);
        return rest.exchange(url("/f/" + token), HttpMethod.POST, new HttpEntity<>(body, h), String.class);
    }

    @Test
    void formLinkIsSentAndRunWaits() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(createChatbot("Bot", formFlow()));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            assertThat(run.getStatus()).isEqualTo(ChatbotRunStatus.WAITING);
            assertThat(run.getWaiting().kind()).isEqualTo(TaskKind.FORM);
            String content = d.requests("sendMessage").get(0).params().get("content").asString();
            assertThat(content).startsWith("Please fill this in: http://pages.test/f/");
            String token = content.substring(content.lastIndexOf('/') + 1);
            assertThat(run.getWaiting().key()).isEqualTo(token);
            assertThat(run.getWaiting().prompt()).isEqualTo("Your details");
            ChatbotForm form = forms.findByToken(token).orElseThrow();
            assertThat(form.getRunId()).isEqualTo(run.getId());
            assertThat(form.getExpiresAt()).isEqualTo(clock.instant().plus(Duration.ofHours(24)));
            ResponseEntity<String> page = getForm(token);
            assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(page.getHeaders().getFirst("X-Robots-Tag")).isEqualTo("noindex");
            assertThat(page.getBody()).contains("Your details").contains("Two quick questions, Veenu").contains("name=\"city\"");
        }
    }

    /** The link's WhatsApp card: Open Graph tags on the page and a drawn image, like the message pages. */
    @Test
    void formPageCarriesALinkCard() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(createChatbot("Bot", formFlow()));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            start(id, chat);
            assertThat(d.requests("sendMessage").get(0).params().get("options").get("linkPreview").asBoolean()).isTrue();
            String content = d.requests("sendMessage").get(0).params().get("content").asString();
            String token = content.substring(content.lastIndexOf('/') + 1);
            String page = getForm(token).getBody();
            assertThat(page).contains("<meta property=\"og:title\" content=\"Your details\">")
                    .contains("<meta property=\"og:description\" content=\"Two quick questions, Veenu\">")
                    .contains("<meta property=\"og:image\" content=\"http://pages.test/f/" + token + "/og.png\">")
                    .contains("<meta property=\"og:url\" content=\"http://pages.test/f/" + token + "\">");
            ResponseEntity<byte[]> png = rest.exchange(url("/f/" + token + "/og.png"), HttpMethod.GET, null, byte[].class);
            assertThat(png.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(png.getHeaders().getContentType().toString()).isEqualTo("image/png");
            assertThat(png.getBody()).isNotNull();
            assertThat(png.getBody()[1]).isEqualTo((byte) 'P');
            assertThat(png.getBody()[2]).isEqualTo((byte) 'N');
            assertThat(rest.exchange(url("/f/nope/og.png"), HttpMethod.GET, null, byte[].class).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Test
    void submitAnswersAndContinues() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(createChatbot("Bot", formFlow()));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            String token = run.getWaiting().key();
            ResponseEntity<String> r = submit(token, Map.of("city", "Pune", "budget", "5,000"));
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(r.getBody()).contains("Got it, thanks!");
            await(() -> runRepo.findById(run.getId()).orElseThrow().getStatus() == ChatbotRunStatus.COMPLETED);
            await(() -> d.requests("sendMessage").size() == 2);
            assertThat(d.requests("sendMessage").get(1).params().get("content").asString()).isEqualTo("Pune / 5000");
            ChatbotRun done = runRepo.findById(run.getId()).orElseThrow();
            @SuppressWarnings("unchecked")
            Map<String, Object> form = (Map<String, Object>) done.getSteps().get(0).result().get("form");
            assertThat(form).containsEntry("city", "Pune");
            assertThat(String.valueOf(form.get("budget"))).isEqualTo("5000");
            assertThat(form.get("submittedAt")).isNotNull();
            assertThat(forms.findByToken(token).orElseThrow().getSubmittedAt()).isNotNull();
        }
    }

    @Test
    void serverValidatesRequired() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(createChatbot("Bot", formFlow()));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            ResponseEntity<String> r = submit(run.getWaiting().key(), Map.of("city", " ", "budget", "abc"));
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(r.getBody()).contains("is required").contains("value=\"abc\"");
            assertThat(runRepo.findById(run.getId()).orElseThrow().getStatus()).isEqualTo(ChatbotRunStatus.WAITING);
        }
    }

    @Test
    void secondSubmitIsClosed() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(createChatbot("Bot", formFlow()));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            String token = run.getWaiting().key();
            assertThat(submit(token, Map.of("city", "Pune")).getStatusCode()).isEqualTo(HttpStatus.OK);
            await(() -> runRepo.findById(run.getId()).orElseThrow().getStatus() == ChatbotRunStatus.COMPLETED);
            ResponseEntity<String> again = submit(token, Map.of("city", "Mumbai"));
            assertThat(again.getStatusCode()).isEqualTo(HttpStatus.GONE);
            assertThat(again.getBody()).contains("This form is closed");
            assertThat(getForm(token).getStatusCode()).isEqualTo(HttpStatus.GONE);
            assertThat(forms.findByToken(token).orElseThrow().getAnswers()).containsEntry("city", "Pune");
            assertThat(runRepo.findById(run.getId()).orElseThrow().getSteps()).hasSize(2);
        }
    }

    @Test
    void expiredTokenClosed() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(createChatbot("Bot", formFlow()));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            clock.advance(Duration.ofHours(25));
            assertThat(getForm(run.getWaiting().key()).getStatusCode()).isEqualTo(HttpStatus.GONE);
            assertThat(submit(run.getWaiting().key(), Map.of("city", "Pune")).getStatusCode()).isEqualTo(HttpStatus.GONE);
            assertThat(runRepo.findById(run.getId()).orElseThrow().getStatus()).isEqualTo(ChatbotRunStatus.WAITING);
        }
    }

    @Test
    void stoppedRunClosesForm() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(createChatbot("Bot", formFlow()));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            String token = run.getWaiting().key();
            runs.stop(run, "stopped in a test", manager.getId());
            assertThat(getForm(token).getStatusCode()).isEqualTo(HttpStatus.GONE);
            assertThat(submit(token, Map.of("city", "Pune")).getStatusCode()).isEqualTo(HttpStatus.GONE);
        }
    }

    @Test
    void unknownTokenIs404() {
        assertThat(getForm("nope").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(submit("nope", Map.of("city", "x")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
