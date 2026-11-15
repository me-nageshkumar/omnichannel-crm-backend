package me.nageshk.omnichannel.ai.turn;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import me.nageshk.omnichannel.ai.api.AiCapabilities;
import me.nageshk.omnichannel.ai.api.AiFinish;
import me.nageshk.omnichannel.ai.api.AiReplyFormat;
import me.nageshk.omnichannel.ai.api.AiUsage;
import me.nageshk.omnichannel.ai.api.StateMode;
import me.nageshk.omnichannel.ai.api.TurnRequest;
import me.nageshk.omnichannel.ai.api.TurnResult;
import me.nageshk.omnichannel.ai.call.AiCall;
import me.nageshk.omnichannel.ai.call.AiCallRepository;
import me.nageshk.omnichannel.ai.call.AiCallStatus;
import me.nageshk.omnichannel.ai.call.AiTurnQueue;
import me.nageshk.omnichannel.ai.config.AiProperties;
import me.nageshk.omnichannel.ai.connection.AiConnectionService;
import me.nageshk.omnichannel.ai.conversation.AiConversation;
import me.nageshk.omnichannel.ai.conversation.AiConversationRepository;
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
import me.nageshk.omnichannel.ai.provider.AiProvider;
import me.nageshk.omnichannel.ai.provider.AiProviderException;
import me.nageshk.omnichannel.ai.provider.AiTool;
import me.nageshk.omnichannel.ai.provider.ProviderReply;
import me.nageshk.omnichannel.ai.provider.ProviderRequest;
import me.nageshk.omnichannel.ai.provider.ToolCall;
import me.nageshk.omnichannel.ai.provider.ToolChoice;
import me.nageshk.omnichannel.common.api.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs one AI turn, provider-neutral (spec §5.4): the effective config, the tools (finish, app-side MCP, hosted),
 * the round trips with the tool loop, retries and a rebuild from the transcript when the provider lost its state,
 * then the conversation's new state. A failure is a FAILED result with a code, never an exception.
 */
@Service
public class AiTurnRunner {

    /** Waits between retries; tests pass one that only records. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration d) throws InterruptedException;
    }

    /** The answer to a finish call when the conversation goes on. */
    static final String FINISHED = "finished";

    private static final Logger log = LoggerFactory.getLogger(AiTurnRunner.class);
    private static final List<Duration> BACKOFF = List.of(Duration.ofSeconds(1), Duration.ofSeconds(4));
    /** The worker's lease is turnTimeout + this (MongoAiTurnQueue); the fallback when a call carries no lease. */
    private static final Duration LEASE_SLACK = Duration.ofSeconds(30);
    /** Kept free at the end of the lease for staging the result and writing the conversation. */
    private static final Duration SAFETY = Duration.ofSeconds(5);
    private static final int EXCERPT = 500;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AiProfileService profiles;
    private final AiConnectionService connections;
    private final McpClient mcp;
    private final AiConversationRepository conversations;
    private final AiCallRepository calls;
    private final AiTurnQueue queue;
    private final AiProperties props;
    private final Clock clock;
    private final Sleeper sleeper;

    @Autowired
    public AiTurnRunner(AiProfileService profiles, AiConnectionService connections, McpClient mcp,
            AiConversationRepository conversations, AiCallRepository calls, AiTurnQueue queue, AiProperties props,
            Clock clock) {
        this(profiles, connections, mcp, conversations, calls, queue, props, clock, d -> Thread.sleep(d.toMillis()));
    }

    AiTurnRunner(AiProfileService profiles, AiConnectionService connections, McpClient mcp,
            AiConversationRepository conversations, AiCallRepository calls, AiTurnQueue queue, AiProperties props,
            Clock clock, Sleeper sleeper) {
        this.profiles = profiles;
        this.connections = connections;
        this.mcp = mcp;
        this.conversations = conversations;
        this.calls = calls;
        this.queue = queue;
        this.props = props;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /** A turn ended without a result; the code goes to {@code aiCalls.errorCode} and the node's {@code errorCode}. */
    private static final class TurnFailure extends RuntimeException {
        final String code;

        TurnFailure(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** An app-side MCP tool as the model knows it. */
    private record AppTool(McpEndpoint endpoint, String tool) {}

    private record Tools(List<AiTool> list, Map<String, AppTool> app, boolean finish) {}

    /** What a turn gathers along the way, kept on the row whether it ends DONE or FAILED. */
    private static final class Tally {
        AiUsage usage = new AiUsage(0, 0, 0, 0);
        final List<AiCall.ToolCallLog> logs = new ArrayList<>();
        boolean toolsUsed;
        boolean rebuilt;

        void add(AiUsage u) {
            if (u == null) return;
            usage = new AiUsage(usage.inputTokens() + u.inputTokens(), usage.outputTokens() + u.outputTokens(),
                    usage.cachedTokens() + u.cachedTokens(), usage.reasoningTokens() + u.reasoningTokens());
        }
    }

    /** Runs the claimed call's turn and fills the call's outcome fields (status, reply, usage …); returns its result. */
    public TurnResult run(AiCall call) {
        long started = System.nanoTime();
        Tally tally = new Tally();
        try {
            if (turn(call, tally)) {
                call.setFinishedAt(clock.instant());
                return call.toResult();
            }
        } catch (TurnFailure f) {
            call.fail(f.code, f.getMessage());
        } catch (AiProviderException e) {
            call.fail(e.code().name(), e.getMessage());
        } catch (NotFoundException e) {
            call.fail("NOT_FOUND", e.getMessage());
        } catch (OptimisticLockingFailureException e) {
            call.fail("CONFLICT", "The conversation changed while the turn ran");
        } catch (RuntimeException e) {
            log.warn("AI turn {} failed", call.getId(), e);
            call.fail("INTERNAL", "The turn failed (" + e.getClass().getSimpleName() + ")");
        }
        call.setUsage(tally.usage);
        call.setToolCalls(tally.logs);
        call.setToolsUsed(tally.toolsUsed);
        call.setLatencyMs((System.nanoTime() - started) / 1_000_000);
        call.setFinishedAt(clock.instant());
        return call.toResult();
    }

    /** True when the call had already been applied by an earlier claim and was finished from its staged result. */
    private boolean turn(AiCall call, Tally tally) {
        Instant start = clock.instant();
        // the lease runs from the claim, not from when a thread took the call; a round (or a retry, or a tool call)
        // starts only while a full provider request or tool call still fits inside it
        Instant leaseEnd = call.getLeaseUntil() != null ? call.getLeaseUntil()
                : start.plus(props.turnTimeout()).plus(LEASE_SLACK);
        Duration longest = props.requestTimeout().compareTo(props.mcp().toolTimeout()) >= 0 ? props.requestTimeout()
                : props.mcp().toolTimeout();
        Instant deadline = leaseEnd.minus(longest).minus(SAFETY);
        if (deadline.isBefore(start)) deadline = start;
        TurnRequest r = call.turnRequest();
        String ws = call.getWorkspaceId();
        AiConversation conv = conversations.findByIdAndWorkspaceId(call.getConversationId(), ws)
                .orElseThrow(() -> new TurnFailure("NOT_FOUND", "The AI conversation is gone"));
        if (call.getId() != null && call.getId().equals(conv.getLastCallId())) {
            // an earlier claim of this call already moved the conversation on, then died before finishing the row
            if (call.getStagedAt() == null) {
                throw new TurnFailure("INTERRUPTED", "The turn was applied but its result was lost");
            }
            call.setStatus(AiCallStatus.DONE);
            call.setErrorCode(null);
            call.setError(null);
            return true;
        }
        ResolvedProfile rp = profiles.resolved(ws, r.profileId());
        AiProvider provider = rp.provider();
        if (!Objects.equals(conv.getProvider(), provider.id())) {
            throw new TurnFailure("PROVIDER_CHANGED", "This conversation was held with " + conv.getProvider()
                    + ", but the profile's connection now uses " + provider.id());
        }

        Map<String, Object> state = copy(conv.getState());
        List<AiItem> transcript = TranscriptItems.items(conv.getTranscript());
        int dropped = conv.getDropped();
        List<String> open = new ArrayList<>(conv.getOpenCallIds() == null ? List.of() : conv.getOpenCallIds());
        boolean superseding = r.supersedesCallId() != null;
        if (superseding) {
            AiCall base = Optional.of(r.supersedesCallId()).filter(id -> id.equals(conv.getLastCallId()))
                    .flatMap(id -> calls.findByIdAndWorkspaceId(id, ws)).filter(c -> c.getStatus() == AiCallStatus.DONE)
                    .orElseThrow(() -> new TurnFailure("SUPERSEDE_REFUSED",
                            "Only the conversation's latest finished turn can be superseded"));
            state = copy(base.getStateBefore());
            int keep = (base.getTranscriptSizeBefore() == null ? 0 : base.getTranscriptSizeBefore()) - dropped;
            transcript = new ArrayList<>(transcript.subList(0, Math.max(0, Math.min(keep, transcript.size()))));
            open = new ArrayList<>(base.getOpenCallIdsBefore() == null ? List.of() : base.getOpenCallIdsBefore());
        }
        call.setStateBefore(copy(state));
        call.setTranscriptSizeBefore(dropped + transcript.size());
        call.setOpenCallIdsBefore(List.copyOf(open));

        // opened here, at claim time, so a key replaced while the turn waited is the one used
        AiCredential credential = connections.credential(ws, rp.connection().getId());
        EffectiveConfig cfg = EffectiveConfig.of(rp.profile(), r.overrides(), r.extraInstructions());
        call.setModel(cfg.model());
        AiCapabilities caps = provider.capabilities();
        Tools tools = tools(rp.profile(), r, caps);
        ToolChoice choice = r.forceFinish() && tools.finish() ? new ToolChoice(ToolChoice.Mode.NAMED, FinishTool.NAME) : null;
        boolean chained = caps.state() == StateMode.CHAINED;

        // this turn's new items: answers to the calls the last turn left open, then the customer's text
        List<AiItem> fresh = new ArrayList<>();
        for (String id : open) {
            AiItem.ToolResultItem answered = result(transcript, id);
            if (answered == null) {
                answered = new AiItem.ToolResultItem(id, FINISHED);
                transcript.add(answered);
            }
            fresh.add(answered);
        }
        open = new ArrayList<>();
        if (r.inputs() != null && !r.inputs().isEmpty()) {
            AiItem.UserText user = new AiItem.UserText(String.join("\n", r.inputs()));
            transcript.add(user);
            fresh.add(user);
        }

        Map<String, String> metadata = new LinkedHashMap<>();
        if (r.runId() != null) metadata.put("runId", r.runId());
        if (r.nodeName() != null) metadata.put("nodeName", r.nodeName());

        boolean withState = chained && !state.isEmpty();
        List<AiItem> input = withState ? fresh : TranscriptItems.sendable(transcript);
        Map<String, Object> sendState = withState ? state : Map.of();
        String text;
        AiFinish finish = null;
        Map<String, Object> newState;
        boolean refused;
        int rounds = 0;
        while (true) {
            if (clock.instant().isAfter(deadline)) {
                throw new TurnFailure("TIMEOUT",
                        "The turn ran out of time (" + props.turnTimeout().toSeconds() + " s) between tool rounds");
            }
            ProviderRequest request = new ProviderRequest(cfg.model(), cfg.instructions(), input, sendState,
                    tools.list(), choice, r.replyFormat(), cfg.reasoningEffort(), cfg.maxOutputTokens(),
                    cfg.temperature(), metadata);
            ProviderReply reply = respond(provider, credential, request, transcript, deadline, ws,
                    rp.connection().getId(), tally);
            tally.add(reply.usage());
            if (reply.usedHostedTools()) tally.toolsUsed = true;
            text = reply.text() == null ? "" : reply.text();
            newState = reply.state() == null ? Map.of() : reply.state();
            if (!text.isBlank()) transcript.add(new AiItem.AssistantText(text));
            refused = reply.refused();
            List<ToolCall> asked = reply.toolCalls() == null ? List.of() : reply.toolCalls();
            for (ToolCall c : asked) transcript.add(new AiItem.ToolCallItem(c.id(), c.name(), c.argumentsJson()));
            if (asked.isEmpty()) {
                // no text, no finish, no tool: a DONE turn would leave the customer in silence
                if (text.isBlank()) {
                    if ("max_output_tokens".equals(reply.incompleteReason())) {
                        throw new TurnFailure("MAX_TOKENS", "The reply was cut at the max output tokens before any text");
                    }
                    throw new TurnFailure("EMPTY_REPLY", reply.incompleteReason() == null ? "The model replied with nothing"
                            : "The reply stopped early (" + reply.incompleteReason() + ") before any text");
                }
                break;
            }

            Optional<ToolCall> finishCall = asked.stream().filter(c -> FinishTool.NAME.equals(c.name())).findFirst();
            if (finishCall.isEmpty() && rounds >= props.maxToolRounds()) {
                throw new TurnFailure("TOOL_ROUNDS",
                        "The model asked for tools more than " + props.maxToolRounds() + " rounds in a row");
            }
            List<AiItem> results = new ArrayList<>();
            for (ToolCall c : asked) {
                if (FinishTool.NAME.equals(c.name())) continue;
                if (clock.instant().isAfter(deadline)) {
                    throw new TurnFailure("TIMEOUT", "The turn ran out of time (" + props.turnTimeout().toSeconds()
                            + " s) before the tool " + c.name());
                }
                results.add(runTool(c, tools.app(), tally));
            }
            transcript.addAll(results);
            if (finishCall.isPresent()) {
                try {
                    finish = FinishTool.parse(finishCall.get().argumentsJson(), r.outcomes(), r.collect());
                } catch (IllegalArgumentException e) {
                    throw new TurnFailure("BAD_FINISH", e.getMessage());
                }
                // answered when the conversation goes on: the finish with "finished", other calls with their result
                open = new ArrayList<>(asked.stream().map(ToolCall::id).toList());
                break;
            }
            rounds++;
            input = chained ? results : TranscriptItems.sendable(transcript);
            sendState = chained ? newState : Map.of();
        }

        AiReplyFormat format = r.replyFormat();
        // a refusal is the model's own words, not the JSON: the node sends it as it is
        if (format != null && format.type() == AiReplyFormat.Type.JSON && !(finish != null && text.isBlank()) && !refused) {
            try {
                JSON.readValue(text, new TypeReference<Map<String, Object>>() {});
            } catch (RuntimeException e) {
                throw new TurnFailure("BAD_JSON", "The reply is not the JSON object the node asked for");
            }
        }

        List<Map<String, Object>> stored = TranscriptItems.maps(transcript);
        int over = stored.size() - AiConversation.MAX_TRANSCRIPT;
        if (over > 0) {
            stored = new ArrayList<>(stored.subList(over, stored.size()));
            dropped += over;
        }
        conv.setState(copy(newState));
        conv.setTranscript(stored);
        conv.setDropped(dropped);
        conv.setOpenCallIds(open);
        if (!superseding) conv.setTurns(conv.getTurns() + 1);
        conv.setLastCallId(call.getId());
        conv.setProfileId(r.profileId());
        conv.setUpdatedAt(clock.instant());

        call.setStatus(AiCallStatus.DONE);
        call.setReply(text);
        call.finish(finish);
        call.setErrorCode(null);
        call.setError(null);
        call.setUsage(tally.usage);
        call.setToolCalls(tally.logs);
        call.setToolsUsed(tally.toolsUsed);
        call.setLatencyMs(Duration.between(start, clock.instant()).toMillis());
        call.setStagedAt(clock.instant());
        // the result goes on the row first, and only while this claim holds it: a copy whose lease ran out never
        // moves the conversation, and a re-claim after a crash here finds the result instead of asking again
        if (!queue.stage(call)) {
            throw new TurnFailure("CLAIM_LOST", "The turn was claimed again while it ran");
        }
        conversations.save(conv);
        return false;
    }

    /** One round trip: RATE_LIMIT/UNAVAILABLE retried twice in the turn's time, an expired state rebuilt once. */
    private ProviderReply respond(AiProvider provider, AiCredential credential, ProviderRequest request,
            List<AiItem> transcript, Instant deadline, String ws, String connectionId, Tally tally) {
        ProviderRequest current = request;
        int retries = 0;
        while (true) {
            try {
                return provider.respond(credential, current);
            } catch (AiProviderException e) {
                switch (e.code()) {
                    case RATE_LIMIT, UNAVAILABLE -> {
                        if (retries >= BACKOFF.size()) throw e;
                        Duration wait = e.retryAfter() != null && !e.retryAfter().isNegative() ? e.retryAfter()
                                : BACKOFF.get(retries);
                        if (clock.instant().plus(wait).isAfter(deadline)) throw e;
                        retries++;
                        try {
                            sleeper.sleep(wait);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw e;
                        }
                    }
                    case STATE_EXPIRED -> {
                        if (tally.rebuilt || current.state() == null || current.state().isEmpty()) throw e;
                        tally.rebuilt = true;
                        current = new ProviderRequest(current.model(), current.instructions(),
                                TranscriptItems.sendable(transcript), Map.of(), current.tools(), current.toolChoice(),
                                current.format(), current.reasoningEffort(), current.maxOutputTokens(),
                                current.temperature(), current.metadata());
                    }
                    case AUTH -> {
                        connections.markInvalid(ws, connectionId, AiConnectionService.REFUSED);
                        throw e;
                    }
                    default -> throw e;
                }
            }
        }
    }

    private Tools tools(AiProfile profile, TurnRequest r, AiCapabilities caps) {
        List<AiTool> list = new ArrayList<>();
        Map<String, AppTool> app = new LinkedHashMap<>();
        Optional<AiTool.FunctionTool> finish = FinishTool.of(r.outcomes(), r.collect());
        finish.ifPresent(list::add);
        for (McpServer s : profile.getMcpServers() == null ? List.<McpServer>of() : profile.getMcpServers()) {
            List<String> allowed = s.getAllowedTools() == null ? List.of() : s.getAllowedTools();
            if (s.getConnectBy() == ConnectBy.APP) {
                McpEndpoint endpoint = profiles.endpoint(s);
                List<McpTool> offered;
                try {
                    offered = mcp.tools(endpoint);
                } catch (McpException e) {
                    throw new TurnFailure("MCP_ERROR", "MCP server " + s.getLabel() + ": " + e.getMessage());
                }
                for (McpTool t : offered) {
                    if (!allowed.isEmpty() && !allowed.contains(t.name())) continue;
                    String name = functionName(s.getLabel(), t.name(), app);
                    list.add(new AiTool.FunctionTool(name, t.description(), t.inputSchema()));
                    app.put(name, new AppTool(endpoint, t.name()));
                }
            } else if (s.getConnectBy() == ConnectBy.PROVIDER && caps.hostedMcp()) {
                McpEndpoint endpoint = profiles.endpoint(s);
                list.add(new AiTool.HostedMcpTool(s.getLabel(), s.getUrl(), endpoint.headers(), List.copyOf(allowed)));
            }
        }
        List<String> stores = profile.getVectorStoreIds() == null ? List.of() : profile.getVectorStoreIds();
        if (!stores.isEmpty() && caps.vectorStores()) list.add(new AiTool.FileSearchTool(List.copyOf(stores)));
        if (profile.isWebSearch() && caps.webSearch()) list.add(new AiTool.WebSearchTool());
        return new Tools(list, app, finish.isPresent());
    }

    /** {@code <label>__<tool>}, cut to the 64 letters, digits, _ and - that function names may hold. */
    private static String functionName(String label, String tool, Map<String, AppTool> taken) {
        String base = (label + "__" + tool).replaceAll("[^A-Za-z0-9_-]", "_");
        if (base.length() > 64) base = base.substring(0, 64);
        String name = base;
        for (int i = 2; taken.containsKey(name) || FinishTool.NAME.equals(name); i++) {
            String suffix = "_" + i;
            name = base.substring(0, Math.min(base.length(), 64 - suffix.length())) + suffix;
        }
        return name;
    }

    /** A failed tool is answered to the model as {@code "error: <message>"}; it does not fail the turn. */
    private AiItem.ToolResultItem runTool(ToolCall c, Map<String, AppTool> app, Tally tally) {
        long started = System.nanoTime();
        AppTool target = app.get(c.name());
        String output;
        boolean ok = false;
        if (target == null) {
            output = "error: there is no tool named " + c.name();
        } else {
            tally.toolsUsed = true;
            try {
                output = mcp.call(target.endpoint(), target.tool(), c.argumentsJson());
                ok = true;
            } catch (McpException e) {
                output = "error: " + e.getMessage();
            } catch (RuntimeException e) {
                log.warn("MCP tool {} failed", c.name(), e);
                output = "error: the tool could not be called";
            }
        }
        if (output == null) output = "";
        tally.logs.add(new AiCall.ToolCallLog(c.name(), (System.nanoTime() - started) / 1_000_000, ok,
                output.length() > EXCERPT ? output.substring(0, EXCERPT) + "…" : output));
        return new AiItem.ToolResultItem(c.id(), output);
    }

    private static AiItem.ToolResultItem result(List<AiItem> transcript, String callId) {
        for (AiItem i : transcript) {
            if (i instanceof AiItem.ToolResultItem t && callId.equals(t.callId())) return t;
        }
        return null;
    }

    private static Map<String, Object> copy(Map<String, Object> m) {
        return m == null ? new LinkedHashMap<>() : new LinkedHashMap<>(m);
    }
}
