package me.nageshk.omnichannel.app.chatbot;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
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
import me.nageshk.omnichannel.db.contact.ContactAttribute;
import me.nageshk.omnichannel.db.contact.ContactAttributeRepository;
import me.nageshk.omnichannel.db.contact.ContactRepository;
import me.nageshk.omnichannel.db.inbox.MessageDirection;
import me.nageshk.omnichannel.db.inbox.MessageSource;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.security.access.AccessService;
import me.nageshk.omnichannel.service.chatbot.node.TaskAnswer;
import me.nageshk.omnichannel.service.chatbot.run.ChatbotRunService;
import me.nageshk.omnichannel.service.cloud.CloudMediaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;

/** Spec §4.2: interactive replies route by button or row, invalid replies are re-asked, every answer type is parsed. */
@SuppressWarnings("unchecked")
class ChatbotRepliesIT extends ChatbotTestBase {

    static final String VEENU = "919799750743@c.us";

    /** A PNG signature padded to the size: enough for the sniffer. */
    static byte[] png(int size) {
        byte[] b = new byte[size];
        byte[] sig = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(sig, 0, b, 0, sig.length);
        return b;
    }

    @Autowired ChatbotRunService runs;
    @Autowired ChatbotRunRepository runRepo;
    @Autowired ContactRepository contacts;
    @Autowired ContactAttributeRepository attributes;
    @Autowired CloudMediaService media;
    @Autowired AccessService access;

    private static Map<String, Object> buttons(String body) {
        return Map.of("message", Map.of("type", "interactive", "interactive", Map.of("type", "button",
                "body", Map.of("text", body), "action", Map.of("buttons", List.of(
                        Map.of("type", "reply", "reply", Map.of("id", "yes", "title", "Yes")),
                        Map.of("type", "reply", "reply", Map.of("id", "no", "title", "No")))))));
    }

    private static Map<String, Object> list() {
        return Map.of("message", Map.of("type", "interactive", "interactive", Map.of("type", "list",
                "body", Map.of("text", "Pick a size"), "action", Map.of("button", "Sizes", "sections", List.of(
                        Map.of("title", "Sizes", "rows", List.of(
                                Map.of("id", "s", "title", "Small"),
                                Map.of("id", "l", "title", "Large"))))))));
    }

    private Map<String, Object> routedFlow(Map<String, Object> ask) {
        return flow(List.of(
                node("ask", "send_message", "Ask", ask),
                node("yes", "send_message", "Yes", textMessage("Great")),
                node("no", "send_message", "No", textMessage("Okay")),
                node("other", "send_message", "Other", textMessage("Hmm"))),
                List.of(edge("ask", "reply:yes", "yes"), edge("ask", "reply:no", "no"), edge("ask", "other", "other")));
    }

    private String publish(JsonNode bot) {
        assertThat(bots(HttpMethod.POST, "/" + bot.get("id").asString() + "/publish", manager, null).getStatusCode().is2xxSuccessful()).isTrue();
        return bot.get("id").asString();
    }

    private JsonNode withRetries(JsonNode bot, int retries) {
        Map<String, Object> settings = new LinkedHashMap<>(mapper.convertValue(bot.get("settings"), Map.class));
        settings.put("invalidReplyRetries", retries);
        Map<String, Object> save = new LinkedHashMap<>();
        save.put("flow", mapper.convertValue(bot.get("flow"), Map.class));
        save.put("settings", settings);
        save.put("version", bot.get("version").asLong());
        return bots(HttpMethod.PUT, "/" + bot.get("id").asString(), manager, save).getBody().get("chatbot");
    }

    private ChatbotRun run(String id) {
        return runRepo.findById(id).orElseThrow();
    }

    private ChatbotRun start(String chatbotId, WaWebChat chat) throws Exception {
        Contact c = contacts.findById(chat.getContactId()).orElseThrow();
        ChatbotRunService.StartResult r = runs.start(brand.getId(), chatbotId, c, chat, StartedBy.test(manager.getId()),
                null, IfRunning.SKIP, true);
        assertThat(r.started()).isTrue();
        r.settled().get(5, TimeUnit.SECONDS);
        return run(r.run().getId());
    }

    private static WaWebMessage inbound(WaWebChat chat, String type, String body) {
        WaWebMessage m = new WaWebMessage();
        m.setId("in-" + System.nanoTime());
        m.setChatId(chat.getId());
        m.setWorkspaceId(chat.getWorkspaceId());
        m.setDirection(MessageDirection.IN);
        m.setSource(MessageSource.CONTACT);
        m.setType(type);
        m.setBody(body);
        m.setTimestamp(Instant.now());
        return m;
    }

    private void answer(WaWebChat chat, WaWebMessage m, String runId, ChatbotRunStatus until) throws Exception {
        assertThat(runs.answer(TaskAnswer.whatsApp(chat.getId(), m))).isTrue();
        await(() -> run(runId).getStatus() == until);
    }

    @Test
    void buttonTitleRoutesToItsOutput() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(createChatbot("Bot", routedFlow(buttons("Book a demo?"))));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            assertThat(run.getStatus()).isEqualTo(ChatbotRunStatus.WAITING);
            // over the bridge the interactive message went out as a page link
            assertThat(d.requests("sendMessage").get(0).params().get("content").asString()).contains("/t/");
            incoming(n1, VEENU, "chat", "yes", "Veenu");
            await(() -> run(run.getId()).getStatus() == ChatbotRunStatus.COMPLETED);
            ChatbotRun done = run(run.getId());
            assertThat(done.getSteps().get(0).output()).isEqualTo("reply:yes");
            assertThat(((Map<?, ?>) done.getVariables().get("Ask")).get("reply")).isEqualTo(Map.of("id", "yes", "title", "Yes", "text", "yes"));
            assertThat(d.requests("sendMessage").get(1).params().get("content").asString()).isEqualTo("Great");
        }
    }

    @Test
    void unmatchedReplyRetriesThenOther() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(withRetries(createChatbot("Bot", routedFlow(buttons("Book a demo?"))), 1));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            answer(chat, inbound(chat, "text", "maybe"), run.getId(), ChatbotRunStatus.WAITING);
            await(() -> d.requests("sendMessage").size() == 3);
            assertThat(d.requests("sendMessage").get(1).params().get("content").asString())
                    .isEqualTo("Sorry, I didn't get that. Please choose one of the options.");
            assertThat(d.requests("sendMessage").get(2).params().get("content").asString()).contains("/t/");
            assertThat(run(run.getId()).getInvalidReplies()).isEqualTo(1);
            assertThat(run(run.getId()).getSteps()).hasSize(1);
            answer(chat, inbound(chat, "text", "dunno"), run.getId(), ChatbotRunStatus.COMPLETED);
            ChatbotRun done = run(run.getId());
            assertThat(done.getSteps().get(0).output()).isEqualTo("other");
            assertThat(d.requests("sendMessage").get(3).params().get("content").asString()).isEqualTo("Hmm");
        }
    }

    @Test
    void listRowByTitle() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        Map<String, Object> f = flow(List.of(
                node("ask", "send_message", "Ask", list()),
                node("l", "send_message", "L", textMessage("Large it is"))),
                List.of(edge("ask", "reply:l", "l")));
        String id = publish(createChatbot("Bot", f));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            answer(chat, inbound(chat, "text", "large"), run.getId(), ChatbotRunStatus.COMPLETED);
            assertThat(run(run.getId()).getSteps().get(0).output()).isEqualTo("reply:l");
            assertThat(d.requests("sendMessage").get(1).params().get("content").asString()).isEqualTo("Large it is");
        }
    }

    @Test
    void ctaMessageDoesNotWait() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        Map<String, Object> cta = Map.of("message", Map.of("type", "interactive", "interactive", Map.of("type", "cta_url",
                "body", Map.of("text", "See our site"), "action", Map.of("name", "cta_url",
                        "parameters", Map.of("display_text", "Open", "url", "https://example.com")))));
        String id = publish(createChatbot("Bot", flow(List.of(node("cta", "send_message", "Cta", cta)), List.of())));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            assertThat(run.getStatus()).isEqualTo(ChatbotRunStatus.COMPLETED);
            assertThat(d.requests("sendMessage")).hasSize(1);
        }
    }

    @Test
    void mediaMessageSendsNatively() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String mediaId = media.upload(access.load(manager), brand.getId(), "image",
                new MockMultipartFile("file", "a.png", "application/octet-stream", png(100))).id();
        Map<String, Object> img = Map.of("message", Map.of("type", "image", "image", Map.of("id", mediaId, "caption", "Hi {{contact.name}}")));
        String id = publish(createChatbot("Bot", flow(List.of(node("img", "send_message", "Img", img)), List.of())));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            assertThat(run.getStatus()).isEqualTo(ChatbotRunStatus.COMPLETED);
            JsonNode options = d.requests("sendMessage").get(0).params().get("options");
            assertThat(options.get("media").get("mimetype").asString()).isEqualTo("image/png");
            assertThat(options.get("caption").asString()).isEqualTo("Hi Veenu");
        }
    }

    private String askFlow(String answerType, Map<String, Object> extra) throws Exception {
        Map<String, Object> settings = new LinkedHashMap<>(Map.of("text", "Your answer?", "answerType", answerType));
        settings.putAll(extra);
        return publish(createChatbot("Bot", flow(List.of(
                node("q", "ask_question", "Q", settings),
                node("ok", "send_message", "Ok", textMessage("Got it")),
                node("none", "send_message", "None", textMessage("Never mind"))),
                List.of(edge("q", "next", "ok"), edge("q", "noAnswer", "none")))));
    }

    @Test
    void locationAnswer() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = askFlow("LOCATION", Map.of());
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            WaWebMessage loc = inbound(chat, "location", null);
            loc.setLocation(new WaWebMessage.Location(12.9716, 77.5946, "Store"));
            answer(chat, loc, run.getId(), ChatbotRunStatus.COMPLETED);
            Map<?, ?> q = (Map<?, ?>) run(run.getId()).getVariables().get("Q");
            assertThat(q.get("answer")).isEqualTo(Map.of("latitude", 12.9716, "longitude", 77.5946));
            assertThat(d.requests("sendMessage").get(1).params().get("content").asString()).isEqualTo("Got it");
        }
    }

    @Test
    void imageAnswerStoresMediaRef() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = askFlow("IMAGE", Map.of());
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            WaWebMessage img = inbound(chat, "image", "");
            img.setMedia(new WaWebMessage.Media("image/jpeg", "a.jpg", 2048L, null, null));
            answer(chat, img, run.getId(), ChatbotRunStatus.COMPLETED);
            Map<?, ?> q = (Map<?, ?>) run(run.getId()).getVariables().get("Q");
            assertThat((Map<String, Object>) q.get("answer")).containsEntry("mimeType", "image/jpeg").containsEntry("messageId", img.getId());
        }
    }

    @Test
    void invalidNumberRetriesPerNode() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = askFlow("NUMBER", Map.of("retries", 0));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            answer(chat, inbound(chat, "text", "abc"), run.getId(), ChatbotRunStatus.COMPLETED);
            ChatbotRun done = run(run.getId());
            assertThat(done.getSteps().get(0).output()).isEqualTo("noAnswer");
            assertThat(d.requests("sendMessage").get(1).params().get("content").asString()).isEqualTo("Never mind");
        }
    }

    @Test
    void numberAnswerIsTypedAndRetriedOnce() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = askFlow("NUMBER", Map.of("retries", 1, "retryMessage", "Digits please"));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            answer(chat, inbound(chat, "text", "abc"), run.getId(), ChatbotRunStatus.WAITING);
            await(() -> d.requests("sendMessage").size() == 2);
            assertThat(d.requests("sendMessage").get(1).params().get("content").asString()).isEqualTo("Digits please");
            answer(chat, inbound(chat, "text", "5,000"), run.getId(), ChatbotRunStatus.COMPLETED);
            Map<?, ?> q = (Map<?, ?>) run(run.getId()).getVariables().get("Q");
            assertThat(new BigDecimal(String.valueOf(q.get("answer")))).isEqualByComparingTo("5000");
        }
    }

    @Test
    void answerSavedToAttribute() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        ContactAttribute a = new ContactAttribute();
        a.setWorkspaceId(brand.getId());
        a.setName("City");
        a.setNameKey("city");
        a.setKey("city");
        a = attributes.save(a);
        String id = askFlow("TEXT", Map.of("attributeId", a.getId(), "saveAs", "city"));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            answer(chat, inbound(chat, "text", "Pune"), run.getId(), ChatbotRunStatus.COMPLETED);
            Contact c = contacts.findById(chat.getContactId()).orElseThrow();
            assertThat(c.getAttributes()).containsEntry(a.getId(), "Pune");
            Map<String, Object> vars = run(run.getId()).getVariables();
            assertThat(((Map<?, ?>) vars.get("city")).get("answer")).isEqualTo("Pune");
            assertThat(((Map<?, ?>) ((Map<?, ?>) vars.get("contact")).get("attributes")).get("city")).isEqualTo("Pune");
        }
    }
}
