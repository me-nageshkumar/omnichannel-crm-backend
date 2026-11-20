package me.nageshk.omnichannel.app.chatbot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import me.nageshk.omnichannel.ai.api.AiUsage;
import me.nageshk.omnichannel.ai.api.TurnResult;
import me.nageshk.omnichannel.ai.api.TurnStatus;
import me.nageshk.omnichannel.ai.call.AiTurnWorker;
import me.nageshk.omnichannel.ai.provider.AiItem;
import me.nageshk.omnichannel.ai.provider.AiProviderException;
import me.nageshk.omnichannel.ai.provider.ProviderReply;
import me.nageshk.omnichannel.ai.provider.ProviderRequest;
import me.nageshk.omnichannel.ai.provider.ToolCall;
import me.nageshk.omnichannel.ai.provider.ToolChoice;
import me.nageshk.omnichannel.app.ai.AiTestBase;
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
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.service.chatbot.run.AiResponseListener;
import me.nageshk.omnichannel.service.chatbot.run.ChatbotRunService;
import me.nageshk.omnichannel.service.chatbot.run.ChatbotRunner;
import me.nageshk.omnichannel.service.chatbot.run.ChatbotTicker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import tools.jackson.databind.JsonNode;

/** Spec §5.5, §5.9: the AI node in single-call mode waits on AI_RESPONSE off the chatbot pool, then goes on. */
@SuppressWarnings("unchecked")
class ChatbotAiSingleIT extends AiTestBase {

    static final String VEENU = "919799750743@c.us";

    @Autowired ChatbotRunService runs;
    @Autowired ChatbotRunRepository runRepo;
    @Autowired ContactRepository contacts;
    @Autowired ChatbotTicker ticker;
    @Autowired AiTurnWorker worker;
    @Autowired AiResponseListener listener;
    @Autowired ThreadPoolTaskExecutor chatbotExecutor;
    @Autowired ChatbotRunner runner;

    private String profileId;

    @BeforeEach
    void setUpProfile() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", "Scripted");
        c.put("provider", "scripted");
        c.put("apiKey", "scripted-key-0123456789");
        String connectionId = ai(HttpMethod.POST, "/connections", manager, c).getBody().get("id").asString();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", "Sales");
        p.put("connectionId", connectionId);
        p.put("model", "scripted-1");
        p.put("instructions", "Be kind.");
        p.put("mcpServers", List.of());
        ResponseEntity<JsonNode> saved = ai(HttpMethod.POST, "/profiles", manager, p);
        assertThat(saved.getStatusCode().is2xxSuccessful()).as(String.valueOf(saved.getBody())).isTrue();
        profileId = saved.getBody().get("id").asString();
    }

    private Map<String, Object> aiSettings(Map<String, Object> extra) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("profileId", profileId);
        s.put("mode", "SINGLE");
        s.put("userMessage", "Classify the lead {{contact.name}}");
        s.putAll(extra);
        return s;
    }

    private static ProviderReply text(String t) {
        return new ProviderReply(t, List.of(), Map.of(), new AiUsage(3, 2, 0, 0), null, false);
    }

    private static ProviderReply finish(String argumentsJson) {
        return new ProviderReply("", List.of(new ToolCall("c1", "finish", argumentsJson)), Map.of(),
                new AiUsage(5, 4, 0, 0), null, false);
    }

    private String publish(JsonNode bot) {
        ResponseEntity<JsonNode> res = bots(HttpMethod.POST, "/" + bot.get("id").asString() + "/publish", manager, null);
        assertThat(res.getStatusCode().is2xxSuccessful()).as(String.valueOf(res.getBody())).isTrue();
        return bot.get("id").asString();
    }

    private JsonNode withSettings(JsonNode bot, Map<String, Object> changes) {
        Map<String, Object> settings = new LinkedHashMap<>(mapper.convertValue(bot.get("settings"), Map.class));
        settings.putAll(changes);
        Map<String, Object> save = new LinkedHashMap<>();
        save.put("flow", mapper.convertValue(bot.get("flow"), Map.class));
        save.put("settings", settings);
        save.put("version", bot.get("version").asLong());
        return bots(HttpMethod.PUT, "/" + bot.get("id").asString(), manager, save).getBody().get("chatbot");
    }

    private ChatbotRun start(String chatbotId, WaWebChat chat) throws Exception {
        Contact c = contacts.findById(chat.getContactId()).orElseThrow();
        ChatbotRunService.StartResult r = runs.start(brand.getId(), chatbotId, c, chat, StartedBy.test(manager.getId()),
                null, IfRunning.SKIP, true);
        assertThat(r.started()).isTrue();
        r.settled().get(5, TimeUnit.SECONDS);
        return run(r.run().getId());
    }

    private ChatbotRun run(String id) {
        return runRepo.findById(id).orElseThrow();
    }

    private void aiTick() throws Exception {
        worker.tick().get(10, TimeUnit.SECONDS);
    }

    private void chatbotTick() throws Exception {
        ticker.tick().get(5, TimeUnit.SECONDS);
    }

    private static Map<String, Object> state(ChatbotRun run, String node) {
        return (Map<String, Object>) run.getVariables().get(node);
    }

    private static String sent(AutomationDevice d, int i) {
        return d.requests("sendMessage").get(i).params().get("content").asString();
    }

    private void assertWaitingOnAi(ChatbotRun run) {
        assertThat(run.getStatus()).isEqualTo(ChatbotRunStatus.WAITING);
        assertThat(run.getWaiting().kind()).isEqualTo(TaskKind.AI_RESPONSE);
        assertThat(run.getWaiting().key()).isEqualTo(state(run, "Classify").get("lastCallId"));
        // the turn waits in the AI queue; no chatbot thread is held for it
        assertThat(chatbotExecutor.getActiveCount()).isZero();
        assertThat(scripted.requests()).isEmpty();
    }

    @Test
    void singleCallStoresReplyAndContinues() throws Exception {
        scripted.reply(text("Sales lead"));
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(createChatbot("Bot", flow(List.of(
                node("ai", "ai", "Classify", aiSettings(Map.of())),
                node("m", "send_message", "Say", textMessage("Got: {{Classify.reply}}"))),
                List.of(edge("ai", "next", "m")))));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            assertWaitingOnAi(run);
            assertThat(run.getWaiting().since()).isEqualTo(clock.instant());
            assertThat(run.getWaiting().expiresAt()).isEqualTo(clock.instant().plus(Duration.ofSeconds(120 * 2 + 30)));
            ChatbotStep step = run.getSteps().get(0);
            assertThat(step.outcome()).isEqualTo(ChatbotStep.Outcome.WAITING);
            assertThat(step.waitedFor().kind()).isEqualTo(TaskKind.AI_RESPONSE);
            Map<String, Object> st = state(run, "Classify");
            assertThat(st).containsEntry("turns", 0).containsEntry("supersedes", 0)
                    .containsKeys("conversationId", "consumedUntil", "startedAt", "lastReply", "usage");
            assertThat(d.requests("sendMessage")).isEmpty();

            aiTick();
            await(() -> run(run.getId()).getStatus() == ChatbotRunStatus.COMPLETED);
            ChatbotRun done = run(run.getId());
            assertThat(sent(d, 0)).isEqualTo("Got: Sales lead");
            Map<String, Object> result = state(done, "Classify");
            assertThat(result).containsEntry("reply", "Sales lead").containsEntry("turns", 1)
                    .containsEntry("outcome", null);
            assertThat(result.get("conversationId")).isNotNull();
            assertThat((Map<String, Object>) result.get("usage")).containsEntry("inputTokens", 3L)
                    .containsEntry("outputTokens", 2L);
            assertThat(done.getSteps().get(0).output()).isEqualTo("next");
            assertThat(done.getSteps().get(0).outcome()).isEqualTo(ChatbotStep.Outcome.DONE);
            ProviderRequest req = scripted.requests().get(0);
            assertThat(req.input()).containsExactly(new AiItem.UserText("Classify the lead Veenu"));
            assertThat(req.toolChoice()).isNull();
        }
    }

    @Test
    void singleWithOutcomesBranches() throws Exception {
        scripted.reply(finish("{\"outcome\":\"b\",\"fields\":{\"city\":\"Pune\"},\"reply\":null}"));
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        Map<String, Object> s = aiSettings(Map.of(
                "outcomes", List.of(Map.of("id", "a", "label", "Hot", "description", "ready to buy"),
                        Map.of("id", "b", "label", "Cold", "description", "just looking")),
                "collect", List.of(Map.of("key", "city", "description", "their city", "type", "TEXT"))));
        String id = publish(createChatbot("Bot", flow(List.of(
                node("ai", "ai", "Classify", s),
                node("a", "send_message", "Hot", textMessage("Hot")),
                node("b", "send_message", "Cold", textMessage("Cold from {{Classify.fields.city}}"))),
                List.of(edge("ai", "outcome:a", "a"), edge("ai", "outcome:b", "b")))));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            assertWaitingOnAi(run);
            aiTick();
            await(() -> run(run.getId()).getStatus() == ChatbotRunStatus.COMPLETED);
            ChatbotRun done = run(run.getId());
            assertThat(done.getSteps().get(0).output()).isEqualTo("outcome:b");
            assertThat(sent(d, 0)).isEqualTo("Cold from Pune");
            assertThat(state(done, "Classify")).containsEntry("outcome", "b")
                    .containsEntry("fields", Map.of("city", "Pune"));
            // outcomes make the finish tool forced: a classifier
            assertThat(scripted.requests().get(0).toolChoice()).isEqualTo(new ToolChoice(ToolChoice.Mode.NAMED, "finish"));
        }
    }

    @Test
    void sendReplyTrueSendsText() throws Exception {
        scripted.reply(text("Hello Veenu, how can I help?"));
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(createChatbot("Bot", flow(List.of(
                node("ai", "ai", "Classify", aiSettings(Map.of("sendReply", true)))), List.of())));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            assertWaitingOnAi(run);
            aiTick();
            await(() -> run(run.getId()).getStatus() == ChatbotRunStatus.COMPLETED);
            assertThat(d.requests("sendMessage")).hasSize(1);
            assertThat(sent(d, 0)).isEqualTo("Hello Veenu, how can I help?");
        }
    }

    @Test
    void unconnectedFailureSaysSoInTheEndReason() throws Exception {
        // a failed AI step with nothing after it must not read like a clean finish
        scripted.fail(new AiProviderException(AiProviderException.Code.AUTH, "Incorrect API key"));
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(createChatbot("Bot", flow(List.of(node("ai", "ai", "Classify", aiSettings(Map.of()))), List.of())));
        ChatbotRun run = start(id, chat);
        assertWaitingOnAi(run);
        aiTick();
        await(() -> !run(run.getId()).getStatus().active());
        assertThat(run(run.getId()).getEndReason()).isEqualTo("Finished after Classify (failure)");
    }

    @Test
    void providerFailureTakesFailure() throws Exception {
        scripted.fail(new AiProviderException(AiProviderException.Code.AUTH, "Incorrect API key sk-xyz"));
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(createChatbot("Bot", flow(List.of(
                node("ai", "ai", "Classify", aiSettings(Map.of())),
                node("ok", "send_message", "Ok", textMessage("ok")),
                node("f", "send_message", "Oops", textMessage("Failed: {{Classify.errorCode}}"))),
                List.of(edge("ai", "next", "ok"), edge("ai", "failure", "f")))));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            assertWaitingOnAi(run);
            aiTick();
            await(() -> run(run.getId()).getStatus() == ChatbotRunStatus.COMPLETED);
            ChatbotRun done = run(run.getId());
            assertThat(sent(d, 0)).isEqualTo("Failed: AUTH");
            ChatbotStep step = done.getSteps().get(0);
            assertThat(step.output()).isEqualTo("failure");
            assertThat(step.summary()).isEqualTo("AI failed: AUTH");
            // the provider's own words stay out of the run
            assertThat(state(done, "Classify")).containsEntry("errorCode", "AUTH").doesNotContainKey("error");
            assertThat(String.valueOf(done.getVariables())).doesNotContain("Incorrect API key");
            assertThat(String.valueOf(done.getSteps())).doesNotContain("Incorrect API key");
        }
    }

    @Test
    void emptyReplyTakesFailure() throws Exception {
        scripted.reply(text(""));
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(createChatbot("Bot", flow(List.of(
                node("ai", "ai", "Classify", aiSettings(Map.of("sendReply", true))),
                node("ok", "send_message", "Ok", textMessage("ok")),
                node("f", "send_message", "Oops", textMessage("Failed: {{Classify.errorCode}}"))),
                List.of(edge("ai", "next", "ok"), edge("ai", "failure", "f")))));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            assertWaitingOnAi(run);
            aiTick();
            await(() -> run(run.getId()).getStatus() == ChatbotRunStatus.COMPLETED);
            ChatbotRun done = run(run.getId());
            // a reply with nothing in it is a failed turn, not a silent success
            assertThat(d.requests("sendMessage")).hasSize(1);
            assertThat(sent(d, 0)).isEqualTo("Failed: EMPTY_REPLY");
            assertThat(done.getSteps().get(0).output()).isEqualTo("failure");
            assertThat(state(done, "Classify")).containsEntry("errorCode", "EMPTY_REPLY");
        }
    }

    @Test
    void jsonReplyWithoutTheMessageFieldTakesFailure() throws Exception {
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of(
                "message", Map.of("type", "string"), "score", Map.of("type", "number")));
        scripted.reply(text("{\"score\":3}"));
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(createChatbot("Bot", flow(List.of(
                node("ai", "ai", "Classify", aiSettings(Map.of("sendReply", true,
                        "replyFormat", Map.of("type", "JSON", "schema", schema, "messageField", "message")))),
                node("ok", "send_message", "Ok", textMessage("ok")),
                node("f", "send_message", "Oops", textMessage("Failed: {{Classify.errorCode}}"))),
                List.of(edge("ai", "next", "ok"), edge("ai", "failure", "f")))));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            assertWaitingOnAi(run);
            aiTick();
            await(() -> run(run.getId()).getStatus() == ChatbotRunStatus.COMPLETED);
            // the raw JSON is never sent to the customer
            assertThat(d.requests("sendMessage")).hasSize(1);
            assertThat(sent(d, 0)).isEqualTo("Failed: NO_MESSAGE_FIELD");
            assertThat(run(run.getId()).getSteps().get(0).output()).isEqualTo("failure");
        }
    }

    @Test
    void expiredWaitTakesFailure() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(createChatbot("Bot", flow(List.of(
                node("ai", "ai", "Classify", aiSettings(Map.of())),
                node("f", "send_message", "Oops", textMessage("Failed: {{Classify.errorCode}}"))),
                List.of(edge("ai", "failure", "f")))));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            assertWaitingOnAi(run);
            clock.advance(Duration.ofSeconds(269));
            chatbotTick();
            Thread.sleep(200);
            assertThat(run(run.getId()).getStatus()).isEqualTo(ChatbotRunStatus.WAITING);
            clock.advance(Duration.ofSeconds(1));
            chatbotTick();
            await(() -> run(run.getId()).getStatus() == ChatbotRunStatus.COMPLETED);
            ChatbotRun done = run(run.getId());
            assertThat(done.getSteps().get(0).output()).isEqualTo("failure");
            assertThat(done.getSteps().get(0).summary()).isEqualTo("AI failed: TIMEOUT");
            assertThat(sent(d, 0)).isEqualTo("Failed: TIMEOUT");
        }
    }

    /** A sent-reply flow whose number has no device yet; the AI has answered and the first send failed. */
    private ChatbotRun answeredWhileOffline(String reply) throws Exception {
        scripted.reply(text(reply));
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(createChatbot("Bot", flow(List.of(
                node("ai", "ai", "Classify", aiSettings(Map.of("sendReply", true))),
                node("m", "send_message", "Say", textMessage("Then: {{Classify.reply}}"))),
                List.of(edge("ai", "next", "m")))));
        ChatbotRun run = start(id, chat);
        assertWaitingOnAi(run);
        aiTick();
        await(() -> run(run.getId()).getRetryAt() != null);
        ChatbotRun retrying = run(run.getId());
        assertThat(retrying.getStatus()).isEqualTo(ChatbotRunStatus.RUNNING);
        assertThat(retrying.getSendAttempts()).isEqualTo(1);
        return retrying;
    }

    private void retryLater() throws Exception {
        clock.advance(Duration.ofSeconds(31));
        runner.retryDue().get(5, TimeUnit.SECONDS);
    }

    private long aiCalls() {
        return mongo.count(new Query(), "aiCalls");
    }

    @Test
    void unsentReplyIsResentNotRegeneratedAndTheRunFailsAfterTheSendAttempts() throws Exception {
        ChatbotRun run = answeredWhileOffline("Your quote is ready");
        retryLater();
        retryLater();
        assertThat(run(run.getId()).getStatus()).isEqualTo(ChatbotRunStatus.RUNNING);
        retryLater();
        ChatbotRun failed = run(run.getId());
        assertThat(failed.getStatus()).isEqualTo(ChatbotRunStatus.FAILED);
        assertThat(failed.getEndReason()).startsWith("could not send");
        // every retry resent the one reply: no new AI call was bought
        aiTick();
        assertThat(aiCalls()).isEqualTo(1);
        assertThat(scripted.requests()).hasSize(1);
    }

    @Test
    void unsentReplyGoesOutWhenTheDeviceComesBack() throws Exception {
        ChatbotRun run = answeredWhileOffline("Your quote is ready");
        retryLater();
        assertThat(run(run.getId()).getSendAttempts()).isEqualTo(2);
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            retryLater();
            await(() -> run(run.getId()).getStatus() == ChatbotRunStatus.COMPLETED);
            assertThat(sent(d, 0)).isEqualTo("Your quote is ready");
            assertThat(sent(d, 1)).isEqualTo("Then: Your quote is ready");
            ChatbotRun done = run(run.getId());
            assertThat(state(done, "Classify")).containsEntry("reply", "Your quote is ready")
                    .doesNotContainKey("pendingSend");
            assertThat(done.getSteps().get(0).output()).isEqualTo("next");
            assertThat(done.getSteps().get(0).outcome()).isEqualTo(ChatbotStep.Outcome.DONE);
        }
        aiTick();
        assertThat(aiCalls()).isEqualTo(1);
        assertThat(scripted.requests()).hasSize(1);
    }

    @Test
    void noReminderWhileAiThinks() throws Exception {
        scripted.reply(text("Sales lead"));
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        JsonNode bot = withSettings(createChatbot("Bot", flow(List.of(
                node("ai", "ai", "Classify", aiSettings(Map.of())),
                node("q", "ask_question", "AskCity", Map.of("text", "City?", "answerType", "TEXT"))),
                List.of(edge("ai", "next", "q")))),
                Map.of("reminderOn", true, "reminderAfterMinutes", 1, "reminderMessage", "Still there?",
                        "endAfterMinutes", 5));
        String id = publish(bot);
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            assertWaitingOnAi(run);
            assertThat(run.getRemindAt()).isNull();
            assertThat(run.getEndAt()).isNull();
            // past the reminder, short of the AI wait's own expiry (4.5 min); no end is scheduled either
            clock.advance(Duration.ofMinutes(4));
            chatbotTick();
            Thread.sleep(200);
            assertThat(d.requests("sendMessage")).isEmpty();
            assertThat(run(run.getId()).getStatus()).isEqualTo(ChatbotRunStatus.WAITING);
            assertThat(run(run.getId()).getWaiting().kind()).isEqualTo(TaskKind.AI_RESPONSE);
            // once the AI answered and the bot waits on the customer, the timers run from then
            aiTick();
            await(() -> run(run.getId()).getWaiting() != null
                    && run(run.getId()).getWaiting().kind() == TaskKind.WHATSAPP_REPLY);
            ChatbotRun asking = run(run.getId());
            assertThat(asking.getRemindAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(1)));
            assertThat(asking.getEndAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(5)));
        }
    }

    @Test
    void earlyDeliveryIsRetriedAndDuplicatesAreHarmless() throws Exception {
        WaWebChat chat = incoming(n1, VEENU, "chat", "hello", "Veenu");
        String id = publish(createChatbot("Bot", flow(List.of(
                node("ai", "ai", "Classify", aiSettings(Map.of())),
                node("m", "send_message", "Say", textMessage("Got: {{Classify.reply}}"))),
                List.of(edge("ai", "next", "m")))));
        try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
            ChatbotRun run = start(id, chat);
            String callId = run.getWaiting().key();
            TurnResult result = new TurnResult(callId, TurnStatus.DONE, "Quick", null, null, Map.of(),
                    new AiUsage(1, 1, 0, 0), false, "conv-1", run.getId(), null, null);
            // as if the turn finished before the runner saved the wait: the run is still RUNNING at the node
            mongo.updateFirst(new Query(Criteria.where("_id").is(run.getId())),
                    new Update().set("status", ChatbotRunStatus.RUNNING.name()), ChatbotRun.class);
            assertThatThrownBy(() -> listener.turnFinished(result)).isInstanceOf(IllegalStateException.class);
            mongo.updateFirst(new Query(Criteria.where("_id").is(run.getId())),
                    new Update().set("status", ChatbotRunStatus.WAITING.name()), ChatbotRun.class);
            listener.turnFinished(result);
            await(() -> run(run.getId()).getStatus() == ChatbotRunStatus.COMPLETED);
            assertThat(sent(d, 0)).isEqualTo("Got: Quick");
            // a second delivery of the same result changes nothing
            listener.turnFinished(result);
            Thread.sleep(200);
            assertThat(d.requests("sendMessage")).hasSize(1);
            // a result for no run at all is ignored
            listener.turnFinished(new TurnResult("other", TurnStatus.DONE, "x", null, null, Map.of(),
                    new AiUsage(0, 0, 0, 0), false, null, null, null, null));
        }
    }
}
