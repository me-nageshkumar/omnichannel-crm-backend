package me.nageshk.omnichannel.ai.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.ai.api.AiField;
import me.nageshk.omnichannel.ai.api.AiFieldType;
import me.nageshk.omnichannel.ai.api.AiFinish;
import me.nageshk.omnichannel.ai.api.AiOutcome;
import me.nageshk.omnichannel.ai.api.AiReplyFormat;
import me.nageshk.omnichannel.ai.api.AiUsage;
import me.nageshk.omnichannel.ai.api.StateMode;
import me.nageshk.omnichannel.ai.api.TurnRequest;
import me.nageshk.omnichannel.ai.api.TurnResult;
import me.nageshk.omnichannel.ai.api.TurnStatus;
import me.nageshk.omnichannel.ai.call.AiCall;
import me.nageshk.omnichannel.ai.call.AiCallStatus;
import me.nageshk.omnichannel.ai.config.AiProperties;
import me.nageshk.omnichannel.ai.connection.AiConnection;
import me.nageshk.omnichannel.ai.connection.AiConnectionService;
import me.nageshk.omnichannel.ai.conversation.AiConversation;
import me.nageshk.omnichannel.ai.conversation.TranscriptItems;
import me.nageshk.omnichannel.ai.mcp.McpClient;
import me.nageshk.omnichannel.ai.mcp.McpEndpoint;
import me.nageshk.omnichannel.ai.mcp.McpException;
import me.nageshk.omnichannel.ai.mcp.McpTool;
import me.nageshk.omnichannel.ai.profile.AiProfile;
import me.nageshk.omnichannel.ai.profile.AiProfileService;
import me.nageshk.omnichannel.ai.profile.AiProfileService.ResolvedProfile;
import me.nageshk.omnichannel.ai.profile.ConnectBy;
import me.nageshk.omnichannel.ai.profile.McpServer;
import me.nageshk.omnichannel.ai.provider.AiCredential;
import me.nageshk.omnichannel.ai.provider.AiItem;
import me.nageshk.omnichannel.ai.provider.AiItem.AssistantText;
import me.nageshk.omnichannel.ai.provider.AiItem.ToolCallItem;
import me.nageshk.omnichannel.ai.provider.AiItem.ToolResultItem;
import me.nageshk.omnichannel.ai.provider.AiItem.UserText;
import me.nageshk.omnichannel.ai.provider.AiProviderException;
import me.nageshk.omnichannel.ai.provider.AiProviderException.Code;
import me.nageshk.omnichannel.ai.provider.AiTool;
import me.nageshk.omnichannel.ai.provider.ProviderReply;
import me.nageshk.omnichannel.ai.provider.ProviderRequest;
import me.nageshk.omnichannel.ai.provider.ToolCall;
import me.nageshk.omnichannel.ai.provider.ToolChoice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The turn engine against the module's scripted provider and a fake MCP client; no Spring. */
class AiTurnRunnerTest {

    private static final String WS = "ws1";
    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");

    private final AiProfileService profiles = mock(AiProfileService.class);
    private final AiConnectionService connections = mock(AiConnectionService.class);
    private final McpClient mcp = mock(McpClient.class);
    private final MemoryStores.Conversations conversations = new MemoryStores.Conversations();
    private final MemoryStores.Calls calls = new MemoryStores.Calls();
    private final MemoryStores.Claims claims = new MemoryStores.Claims();
    private final List<Duration> sleeps = new ArrayList<>();
    private Instant now = NOW;
    private final Clock clock = new Clock() {
        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    };

    private ScriptedProvider provider;
    private AiProfile profile;
    private AiConnection connection;
    private AiTurnRunner runner;

    @BeforeEach
    void setUp() {
        profile = new AiProfile();
        profile.setId("p1");
        profile.setWorkspaceId(WS);
        profile.setName("Sales");
        profile.setConnectionId("conn1");
        profile.setModel("m1");
        profile.setInstructions("Be kind.");
        connection = new AiConnection();
        connection.setId("conn1");
        connection.setWorkspaceId(WS);
        use(new ScriptedProvider("scripted", StateMode.TRANSCRIPT));
        when(connections.credential(WS, "conn1")).thenReturn(new AiCredential("k1"));
        runner = runner(8);
    }

    private AiTurnRunner runner(int maxToolRounds) {
        AiProperties props = new AiProperties(false, Duration.ofSeconds(2), 8, Duration.ofSeconds(60),
                Duration.ofSeconds(120), 2, maxToolRounds, Duration.ofSeconds(10), new AiProperties.OpenAi("x"),
                new AiProperties.Mcp(Duration.ofSeconds(20), 65536, Duration.ofMinutes(5)));
        return new AiTurnRunner(profiles, connections, mcp, conversations, calls, claims, props, clock, sleeps::add);
    }

    private void use(ScriptedProvider p) {
        provider = p;
        connection.setProvider(p.id());
        when(profiles.resolved(WS, "p1")).thenReturn(new ResolvedProfile(profile, connection, p));
    }

    private static TurnRequest request(String conversationId, String... inputs) {
        return new TurnRequest(WS, "run1", "bot1", "Ask", "p1", null, null, List.of(inputs), List.of(), List.of(),
                false, null, conversationId, null);
    }

    private static TurnRequest with(TurnRequest r, List<AiOutcome> outcomes, List<AiField> collect, boolean force,
            AiReplyFormat format, String supersedes) {
        return new TurnRequest(r.workspaceId(), r.runId(), r.chatbotId(), r.nodeName(), r.profileId(), r.overrides(),
                r.extraInstructions(), r.inputs(), outcomes, collect, force, format, r.conversationId(), supersedes);
    }

    /** What the gateway and worker do around a run: start the conversation, insert the row, run, keep the row. */
    private TurnResult turn(TurnRequest r) {
        String conversationId = r.conversationId();
        if (conversationId == null) {
            conversationId = conversations.save(AiConversation.start(r, provider.id(), NOW)).getId();
        }
        AiCall call = calls.save(AiCall.pending(r, conversationId, provider.id(), "m1", NOW));
        TurnResult result = runner.run(call);
        calls.save(call);
        return result;
    }

    private AiCall row(TurnResult r) {
        return calls.rows.get(r.callId());
    }

    private List<AiItem> transcript(String conversationId) {
        return TranscriptItems.items(conversations.get(conversationId).getTranscript());
    }

    private McpServer appServer() {
        McpServer s = new McpServer();
        s.setId("s1");
        s.setLabel("crm");
        s.setUrl("https://mcp.example.com/mcp");
        s.setConnectBy(ConnectBy.APP);
        s.setAllowedTools(List.of("lookup"));
        McpEndpoint e = new McpEndpoint(s.getUrl(), Map.of());
        when(profiles.endpoint(any(McpServer.class))).thenReturn(e);
        when(mcp.tools(e)).thenReturn(List.of(
                new McpTool("lookup", "Finds a customer", Map.of("type", "object")),
                new McpTool("delete", "Deletes everything", Map.of("type", "object"))));
        return s;
    }

    @Test
    void singleTextTurn() {
        provider.text("r1", "Hello");

        TurnResult r = turn(request(null, "Hi"));

        assertThat(r.status()).isEqualTo(TurnStatus.DONE);
        assertThat(r.text()).isEqualTo("Hello");
        assertThat(r.usage()).isEqualTo(new AiUsage(10, 5, 1, 0));
        assertThat(r.toolsUsed()).isFalse();
        assertThat(r.finish()).isNull();
        assertThat(r.runId()).isEqualTo("run1");
        assertThat(r.conversationId()).isNotNull();
        ProviderRequest sent = provider.last();
        assertThat(sent.model()).isEqualTo("m1");
        assertThat(sent.instructions()).isEqualTo("Be kind.");
        assertThat(sent.input()).containsExactly(new UserText("Hi"));
        assertThat(sent.tools()).isEmpty();
        assertThat(sent.toolChoice()).isNull();
        assertThat(sent.metadata()).containsEntry("runId", "run1").containsEntry("nodeName", "Ask");
        assertThat(provider.keys).containsExactly("k1");
        AiCall row = row(r);
        assertThat(row.getStatus()).isEqualTo(AiCallStatus.DONE);
        assertThat(row.getInput()).isEqualTo("Hi");
        assertThat(row.getReply()).isEqualTo("Hello");
        assertThat(row.getFinishedAt()).isEqualTo(NOW);
        assertThat(transcript(r.conversationId())).containsExactly(new UserText("Hi"), new AssistantText("Hello"));
        assertThat(conversations.get(r.conversationId()).getTurns()).isEqualTo(1);
        assertThat(conversations.get(r.conversationId()).getLastCallId()).isEqualTo(r.callId());
    }

    @Test
    void transcriptProviderGetsWholeHistory() {
        provider.text("r1", "Hello").text("r2", "Fine");
        TurnResult first = turn(request(null, "Hi"));

        turn(request(first.conversationId(), "How are you?", "And you?"));

        ProviderRequest second = provider.last();
        assertThat(second.state()).isEmpty();
        assertThat(second.instructions()).isEqualTo("Be kind.");
        assertThat(second.input()).containsExactly(new UserText("Hi"), new AssistantText("Hello"),
                new UserText("How are you?\nAnd you?"));
        assertThat(transcript(first.conversationId())).hasSize(4);
    }

    @Test
    void chainedProviderGetsOnlyNewInputs() {
        use(new ScriptedProvider("chained", StateMode.CHAINED));
        provider.text("r1", "Hello").text("r2", "Fine");
        TurnResult first = turn(request(null, "Hi"));

        TurnResult second = turn(request(first.conversationId(), "Next"));

        assertThat(provider.requests.get(0).state()).isEmpty();
        ProviderRequest sent = provider.last();
        assertThat(sent.state()).isEqualTo(Map.of("responseId", "r1"));
        assertThat(sent.instructions()).isEqualTo("Be kind.");
        assertThat(sent.input()).containsExactly(new UserText("Next"));
        assertThat(second.conversationId()).isEqualTo(first.conversationId());
        AiConversation c = conversations.get(first.conversationId());
        assertThat(c.getState()).isEqualTo(Map.of("responseId", "r2"));
        assertThat(TranscriptItems.items(c.getTranscript())).containsExactly(new UserText("Hi"),
                new AssistantText("Hello"), new UserText("Next"), new AssistantText("Fine"));
    }

    @Test
    void finishEndsLoopAndRecordsOpenCall() {
        use(new ScriptedProvider("chained", StateMode.CHAINED));
        provider.calls("r1", new ToolCall("c1", "finish", "{\"outcome\":\"b\",\"fields\":{\"city\":\"Pune\"},\"reply\":\"Bye\"}"));
        TurnRequest r = with(request(null, "I want to book"),
                List.of(new AiOutcome("a", "Booked", null), new AiOutcome("b", "Later", null)),
                List.of(new AiField("city", "City", AiFieldType.TEXT)), true, null, null);

        TurnResult result = turn(r);

        assertThat(result.status()).isEqualTo(TurnStatus.DONE);
        assertThat(result.finish()).isEqualTo(new AiFinish("b", Map.of("city", "Pune"), "Bye"));
        assertThat(result.toolsUsed()).isFalse();
        assertThat(provider.requests).hasSize(1);
        ProviderRequest sent = provider.last();
        assertThat(sent.tools()).extracting(t -> ((AiTool.FunctionTool) t).name()).containsExactly("finish");
        assertThat(sent.toolChoice()).isEqualTo(new ToolChoice(ToolChoice.Mode.NAMED, "finish"));
        assertThat(conversations.get(result.conversationId()).getOpenCallIds()).containsExactly("c1");
        assertThat(row(result).finish()).isEqualTo(result.finish());
    }

    @Test
    void nextTurnAnswersOpenFinishCall() {
        use(new ScriptedProvider("chained", StateMode.CHAINED));
        provider.calls("r1", new ToolCall("c1", "finish", "{\"outcome\":\"a\",\"fields\":{},\"reply\":null}"))
                .text("r2", "Again?");
        TurnRequest r = with(request(null, "Hi"), List.of(new AiOutcome("a", "Done", null)), List.of(), false, null,
                null);
        TurnResult first = turn(r);
        assertThat(first.finish()).isEqualTo(new AiFinish("a", Map.of(), null));
        assertThat(provider.last().toolChoice()).isNull();

        turn(request(first.conversationId(), "Again"));

        ProviderRequest sent = provider.last();
        assertThat(sent.state()).isEqualTo(Map.of("responseId", "r1"));
        assertThat(sent.input()).containsExactly(new ToolResultItem("c1", "finished"), new UserText("Again"));
        AiConversation c = conversations.get(first.conversationId());
        assertThat(c.getOpenCallIds()).isEmpty();
        assertThat(TranscriptItems.items(c.getTranscript())).containsExactly(new UserText("Hi"),
                new ToolCallItem("c1", "finish", "{\"outcome\":\"a\",\"fields\":{},\"reply\":null}"),
                new ToolResultItem("c1", "finished"), new UserText("Again"), new AssistantText("Again?"));
    }

    @Test
    void appSideToolLoop() {
        profile.setMcpServers(List.of(appServer()));
        McpEndpoint e = profiles.endpoint(profile.getMcpServers().get(0));
        when(mcp.call(e, "lookup", "{\"id\":1}")).thenReturn("found Asha");
        when(mcp.call(e, "lookup", "{\"id\":2}")).thenThrow(new McpException("no such customer"));
        provider.calls("r1", new ToolCall("t1", "crm__lookup", "{\"id\":1}"), new ToolCall("t2", "crm__lookup", "{\"id\":2}"))
                .text("r2", "Asha it is");

        TurnResult r = turn(request(null, "Who is 1?"));

        assertThat(r.status()).isEqualTo(TurnStatus.DONE);
        assertThat(r.text()).isEqualTo("Asha it is");
        assertThat(r.toolsUsed()).isTrue();
        assertThat(r.usage()).isEqualTo(new AiUsage(20, 10, 2, 0));
        assertThat(provider.requests.get(0).tools()).containsExactly(
                new AiTool.FunctionTool("crm__lookup", "Finds a customer", Map.of("type", "object")));
        assertThat(provider.requests.get(1).input()).containsExactly(new UserText("Who is 1?"),
                new ToolCallItem("t1", "crm__lookup", "{\"id\":1}"), new ToolCallItem("t2", "crm__lookup", "{\"id\":2}"),
                new ToolResultItem("t1", "found Asha"), new ToolResultItem("t2", "error: no such customer"));
        assertThat(row(r).getToolCalls()).extracting(AiCall.ToolCallLog::name, AiCall.ToolCallLog::ok,
                AiCall.ToolCallLog::resultExcerpt).containsExactly(
                org.assertj.core.groups.Tuple.tuple("crm__lookup", true, "found Asha"),
                org.assertj.core.groups.Tuple.tuple("crm__lookup", false, "error: no such customer"));
    }

    @Test
    void toolRoundsExhausted() {
        runner = runner(3);
        profile.setMcpServers(List.of(appServer()));
        when(mcp.call(any(), eq("lookup"), anyString())).thenReturn("again");
        for (int i = 0; i < 4; i++) provider.calls("r" + i, new ToolCall("t" + i, "crm__lookup", "{}"));

        TurnResult r = turn(request(null, "Loop"));

        assertThat(r.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(r.errorCode()).isEqualTo("TOOL_ROUNDS");
        assertThat(provider.requests).hasSize(4);
        assertThat(row(r).getStatus()).isEqualTo(AiCallStatus.FAILED);
        assertThat(conversations.get(r.conversationId()).getTurns()).isZero();
    }

    @Test
    void deadlineComesFromTheLease() {
        profile.setMcpServers(List.of(appServer()));
        when(mcp.call(any(), eq("lookup"), anyString())).thenAnswer(inv -> {
            now = now.plusSeconds(40);
            return "slow";
        });
        provider.calls("r1", new ToolCall("t1", "crm__lookup", "{}")).text("r2", "late");
        TurnRequest r = request(null, "Hi");
        String conversationId = conversations.save(AiConversation.start(r, provider.id(), NOW)).getId();
        AiCall call = calls.save(AiCall.pending(r, conversationId, provider.id(), "m1", NOW));
        // claimed 50 s before it got a thread: 100 s of lease left, a request may take 60 s, 5 s margin
        call.setLeaseUntil(NOW.plusSeconds(100));

        TurnResult result = runner.run(call);

        assertThat(result.errorCode()).isEqualTo("TIMEOUT");
        assertThat(provider.requests).hasSize(1);
    }

    @Test
    void noToolCallPastTheDeadline() {
        profile.setMcpServers(List.of(appServer()));
        when(mcp.call(any(), eq("lookup"), eq("{\"id\":1}"))).thenAnswer(inv -> {
            now = now.plusSeconds(90);
            return "slow";
        });
        provider.calls("r1", new ToolCall("t1", "crm__lookup", "{\"id\":1}"),
                new ToolCall("t2", "crm__lookup", "{\"id\":2}"));

        TurnResult r = turn(request(null, "Hi"));

        assertThat(r.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(r.errorCode()).isEqualTo("TIMEOUT");
        verify(mcp, never()).call(any(), eq("lookup"), eq("{\"id\":2}"));
        assertThat(row(r).getToolCalls()).hasSize(1);
    }

    @Test
    void lostClaimLeavesTheConversation() {
        claims.held = false;
        provider.text("r1", "Hello");

        TurnResult r = turn(request(null, "Hi"));

        assertThat(r.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(r.errorCode()).isEqualTo("CLAIM_LOST");
        AiConversation c = conversations.get(r.conversationId());
        assertThat(c.getTurns()).isZero();
        assertThat(c.getTranscript()).isEmpty();
        assertThat(c.getLastCallId()).isNull();
    }

    @Test
    void appliedTurnIsNotRunAgain() {
        provider.text("r1", "Hello");
        TurnRequest r = request(null, "Hi");
        String conversationId = conversations.save(AiConversation.start(r, provider.id(), NOW)).getId();
        AiCall call = calls.save(AiCall.pending(r, conversationId, provider.id(), "m1", NOW));
        call.setStatus(AiCallStatus.RUNNING);
        call.setAttempts(1);
        assertThat(runner.run(call).status()).isEqualTo(TurnStatus.DONE);
        assertThat(claims.staged).containsExactly(call.getId());

        // the worker died between the conversation write and the finish: the row is claimed again as staged
        AiCall again = claims.stagedCopy(call.getId());
        again.setAttempts(2);
        TurnResult r2 = runner.run(again);

        assertThat(r2.status()).isEqualTo(TurnStatus.DONE);
        assertThat(r2.text()).isEqualTo("Hello");
        assertThat(r2.usage()).isEqualTo(new AiUsage(10, 5, 1, 0));
        assertThat(provider.requests).as("the provider is not asked again").hasSize(1);
        assertThat(conversations.get(conversationId).getTurns()).isEqualTo(1);
        assertThat(transcript(conversationId)).hasSize(2);

        // applied but nothing staged (cannot happen in order, but never apply twice)
        AiCall bare = AiCall.pending(r, conversationId, provider.id(), "m1", NOW);
        bare.setId(call.getId());
        TurnResult r3 = runner.run(bare);
        assertThat(r3.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(r3.errorCode()).isEqualTo("INTERRUPTED");
        assertThat(provider.requests).hasSize(1);
    }

    @Test
    void turnStopsBeforeItsLeaseRunsOut() {
        profile.setMcpServers(List.of(appServer()));
        when(mcp.call(any(), eq("lookup"), anyString())).thenAnswer(inv -> {
            now = now.plusSeconds(91);
            return "slow";
        });
        provider.calls("r1", new ToolCall("t1", "crm__lookup", "{}")).text("r2", "late");

        TurnResult r = turn(request(null, "Hi"));

        assertThat(r.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(r.errorCode()).as("lease 150 s, a request may take 60 s: no round starts after 90 s")
                .isEqualTo("TIMEOUT");
        assertThat(provider.requests).hasSize(1);
    }

    @Test
    void stateExpiredRebuildsFromTranscript() {
        use(new ScriptedProvider("chained", StateMode.CHAINED));
        provider.text("r1", "Hello").fail(new AiProviderException(Code.STATE_EXPIRED, "gone")).text("r2", "Fine");
        TurnResult first = turn(request(null, "Hi"));

        TurnResult second = turn(request(first.conversationId(), "Next"));

        assertThat(second.status()).isEqualTo(TurnStatus.DONE);
        assertThat(provider.requests.get(1).state()).isEqualTo(Map.of("responseId", "r1"));
        assertThat(provider.requests.get(1).input()).containsExactly(new UserText("Next"));
        assertThat(provider.requests.get(2).state()).isEmpty();
        assertThat(provider.requests.get(2).input()).containsExactly(new UserText("Hi"), new AssistantText("Hello"),
                new UserText("Next"));
        assertThat(conversations.get(first.conversationId()).getState()).isEqualTo(Map.of("responseId", "r2"));
    }

    @Test
    void rateLimitRetriedThenSucceeds() {
        provider.fail(new AiProviderException(Code.RATE_LIMIT, "slow down"))
                .fail(new AiProviderException(Code.UNAVAILABLE, "down"))
                .text("r1", "Made it");

        TurnResult r = turn(request(null, "Hi"));

        assertThat(r.status()).isEqualTo(TurnStatus.DONE);
        assertThat(r.text()).isEqualTo("Made it");
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(4));

        sleeps.clear();
        provider.fail(new AiProviderException(Code.RATE_LIMIT, "slow", Duration.ofSeconds(7)))
                .fail(new AiProviderException(Code.RATE_LIMIT, "slow"))
                .fail(new AiProviderException(Code.RATE_LIMIT, "still slow"));
        TurnResult failed = turn(request(r.conversationId(), "Again"));
        assertThat(failed.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(failed.errorCode()).isEqualTo("RATE_LIMIT");
        assertThat(failed.error()).isEqualTo("still slow");
        assertThat(sleeps).containsExactly(Duration.ofSeconds(7), Duration.ofSeconds(4));
    }

    @Test
    void authOnAConnectionWithSettingsNamesThem() {
        when(connections.credential(WS, "conn1"))
                .thenReturn(new AiCredential("k1", java.util.Map.of("organization", "org_wrong")));
        provider.fail(new AiProviderException(Code.AUTH, "No such organization"));

        turn(request(null, "Hi"));

        verify(connections).markInvalid(WS, "conn1",
                "The provider refused the key or the connection's settings: No such organization");
    }

    @Test
    void authMarksConnectionInvalid() {
        provider.fail(new AiProviderException(Code.AUTH, "Incorrect API key"));

        TurnResult r = turn(request(null, "Hi"));

        assertThat(r.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(r.errorCode()).isEqualTo("AUTH");
        verify(connections).markInvalid(eq(WS), eq("conn1"), anyString());
        assertThat(sleeps).isEmpty();
    }

    @Test
    void providerChangedFails() {
        provider.text("r1", "Hello");
        TurnResult first = turn(request(null, "Hi"));
        ScriptedProvider other = new ScriptedProvider("other", StateMode.CHAINED);
        connection.setProvider("other");
        when(profiles.resolved(WS, "p1")).thenReturn(new ResolvedProfile(profile, connection, other));

        AiCall call = calls.save(AiCall.pending(request(first.conversationId(), "Next"), first.conversationId(),
                "other", "m1", NOW));
        TurnResult r = runner.run(call);

        assertThat(r.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(r.errorCode()).isEqualTo("PROVIDER_CHANGED");
        assertThat(other.requests).isEmpty();
        verify(connections, never()).markInvalid(any(), any(), any());
    }

    @Test
    void badJsonFails() {
        AiReplyFormat json = new AiReplyFormat(AiReplyFormat.Type.JSON,
                Map.of("type", "object", "properties", Map.of("message", Map.of("type", "string"))), "message");
        provider.text("r1", "{\"message\":\"Hi there\"}").text("r2", "Sure! {not json");

        TurnResult ok = turn(with(request(null, "Hi"), List.of(), List.of(), false, json, null));
        assertThat(ok.status()).isEqualTo(TurnStatus.DONE);
        assertThat(ok.json()).isEqualTo(Map.of("message", "Hi there"));
        assertThat(provider.last().format()).isEqualTo(json);
        assertThat(calls.rows.get(ok.callId()).toResult().json()).isEqualTo(Map.of("message", "Hi there"));

        TurnResult bad = turn(with(request(ok.conversationId(), "Again"), List.of(), List.of(), false, json, null));
        assertThat(bad.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(bad.errorCode()).isEqualTo("BAD_JSON");
    }

    @Test
    void emptyReplyFails() {
        provider.text("r1", "  ");

        TurnResult r = turn(request(null, "Hi"));

        // a turn that says nothing and calls nothing would leave the customer in silence
        assertThat(r.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(r.errorCode()).isEqualTo("EMPTY_REPLY");
        assertThat(conversations.get(r.conversationId()).getLastCallId()).isNull();
    }

    @Test
    void emptyReplyCutAtMaxTokensFailsAsMaxTokens() {
        provider.add(new ProviderReply("", List.of(), Map.of(), new AiUsage(10, 500, 0, 500), "r1",
                false, "max_output_tokens", false));

        TurnResult r = turn(request(null, "Hi"));

        assertThat(r.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(r.errorCode()).isEqualTo("MAX_TOKENS");
        assertThat(r.usage().outputTokens()).isEqualTo(500);
    }

    @Test
    void cutReplyWithTextIsStillAReply() {
        provider.add(new ProviderReply("The first part", List.of(), Map.of(), new AiUsage(1, 1, 0, 0), "r1",
                false, "max_output_tokens", false));

        TurnResult r = turn(request(null, "Hi"));

        assertThat(r.status()).isEqualTo(TurnStatus.DONE);
        assertThat(r.text()).isEqualTo("The first part");
    }

    @Test
    void refusalIsTheReplyEvenWithAJsonFormat() {
        AiReplyFormat json = new AiReplyFormat(AiReplyFormat.Type.JSON,
                Map.of("type", "object", "properties", Map.of("message", Map.of("type", "string"))), "message");
        provider.add(new ProviderReply("I can't help with that.", List.of(), Map.of(), new AiUsage(1, 1, 0, 0), "r1",
                false, null, true));

        TurnResult r = turn(with(request(null, "Hi"), List.of(), List.of(), false, json, null));

        // not BAD_JSON: the node sends the refusal's words as they are
        assertThat(r.status()).isEqualTo(TurnStatus.DONE);
        assertThat(r.text()).isEqualTo("I can't help with that.");
        assertThat(r.json()).isNull();
    }

    @Test
    void supersedeRestoresBaseState() {
        use(new ScriptedProvider("chained", StateMode.CHAINED));
        provider.text("r1", "A1").text("r2", "A2").text("r3", "A3");
        TurnResult first = turn(request(null, "Hi"));
        TurnResult second = turn(request(first.conversationId(), "Next"));

        TurnResult third = turn(with(request(first.conversationId(), "Next", "More"), List.of(), List.of(), false,
                null, second.callId()));

        assertThat(third.status()).isEqualTo(TurnStatus.DONE);
        ProviderRequest sent = provider.last();
        assertThat(sent.state()).isEqualTo(Map.of("responseId", "r1"));
        assertThat(sent.input()).containsExactly(new UserText("Next\nMore"));
        AiConversation c = conversations.get(first.conversationId());
        assertThat(c.getState()).isEqualTo(Map.of("responseId", "r3"));
        assertThat(TranscriptItems.items(c.getTranscript())).containsExactly(new UserText("Hi"),
                new AssistantText("A1"), new UserText("Next\nMore"), new AssistantText("A3"));
        assertThat(c.getLastCallId()).isEqualTo(third.callId());
    }

    @Test
    void supersedeOfOlderCallRefused() {
        provider.text("r1", "A1").text("r2", "A2");
        TurnResult first = turn(request(null, "Hi"));
        turn(request(first.conversationId(), "Next"));

        TurnResult r = turn(with(request(first.conversationId(), "More"), List.of(), List.of(), false, null,
                first.callId()));

        assertThat(r.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(r.errorCode()).isEqualTo("SUPERSEDE_REFUSED");
        assertThat(provider.requests).hasSize(2);
        assertThat(transcript(first.conversationId())).hasSize(4);
    }

    @Test
    void toolsUsedFlag() {
        profile.setMcpServers(List.of(appServer()));
        when(mcp.call(any(), eq("lookup"), anyString())).thenReturn("ok");
        provider.calls("r1", new ToolCall("t1", "crm__lookup", "{}")).text("r2", "Done");
        assertThat(turn(request(null, "app")).toolsUsed()).as("app-side MCP").isTrue();

        profile.setMcpServers(List.of());
        provider.add(provider.reply("r3", "Booked", List.of(), true));
        assertThat(turn(request(null, "hosted")).toolsUsed()).as("hosted MCP").isTrue();

        profile.setVectorStoreIds(List.of("vs1"));
        provider.text("r4", "From the docs");
        assertThat(turn(request(null, "files")).toolsUsed()).as("file search only").isFalse();
        assertThat(provider.last().tools()).containsExactly(new AiTool.FileSearchTool(List.of("vs1")));
    }
}
