package me.nageshk.omnichannel.service.chatbot.node;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import me.nageshk.omnichannel.ai.api.AiCapabilities;
import me.nageshk.omnichannel.ai.api.AiField;
import me.nageshk.omnichannel.ai.api.AiFinish;
import me.nageshk.omnichannel.ai.api.AiGateway;
import me.nageshk.omnichannel.ai.api.AiOutcome;
import me.nageshk.omnichannel.ai.api.AiOverrides;
import me.nageshk.omnichannel.ai.api.AiProfileInfo;
import me.nageshk.omnichannel.ai.api.AiReplyFormat;
import me.nageshk.omnichannel.ai.api.AiUsage;
import me.nageshk.omnichannel.ai.api.TurnRequest;
import me.nageshk.omnichannel.ai.api.TurnResult;
import me.nageshk.omnichannel.ai.api.TurnStatus;
import me.nageshk.omnichannel.common.api.CodedException;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.db.chatbot.TaskKind;
import me.nageshk.omnichannel.db.chatbot.Waiting;
import me.nageshk.omnichannel.service.chatbot.flow.Flow;
import me.nageshk.omnichannel.service.chatbot.flow.FlowEdge;
import me.nageshk.omnichannel.service.chatbot.flow.FlowNode;
import me.nageshk.omnichannel.service.chatbot.flow.FlowParser;
import me.nageshk.omnichannel.service.chatbot.flow.NodeType;
import me.nageshk.omnichannel.service.chatbot.flow.Outputs;
import me.nageshk.omnichannel.service.chatbot.flow.settings.AiNodeSettings;
import me.nageshk.omnichannel.service.chatbot.flow.settings.AiNodeSettings.Mode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The AI node (spec §5.9). It submits a turn to the AI module and waits on AI_RESPONSE, so no chatbot thread is held
 * while the model thinks (§5.5). Its state lives under {@code variables[nodeName]} while it waits; the result
 * replaces it when it goes on. Single-call mode runs here; conversation mode runs in {@link AiConversationFlow}. Every
 * AI text the customer gets is WhatsApp-formatted ({@link WhatsAppFormat}) and sent through {@link AiPendingSend}. The
 * bean is named apart from the AI module's {@code aiExecutor} thread pool.
 */
@Component("aiNodeExecutor")
public class AiExecutor extends BaseExecutor {

    static final Pattern OUTCOME_ID = Pattern.compile("[a-z0-9_]{1,30}");
    static final Pattern COLLECT_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,39}");
    static final Set<String> EFFORTS = Set.of("minimal", "low", "medium", "high");
    static final int MAX_TEXT = 4096;
    static final int MAX_INSTRUCTIONS = 20000;
    static final int MAX_LIST = 20;
    private final AiGateway gateway;
    /** How long a turn may take before the ticker fails it: every attempt's timeout, and a margin. */
    private final Duration aiWait;
    private final AiConversationFlow conversation;

    /**
     * The two timing values are read from the AI module's own {@code omnichannel.ai.*} keys (the service may import
     * only {@code ai.api}, so not {@code AiProperties}); the defaults match it.
     */
    public AiExecutor(FlowParser parser, AiGateway gateway, AiInputs inputs,
            @Value("${omnichannel.ai.turn-timeout:120s}") Duration turnTimeout,
            @Value("${omnichannel.ai.attempts:2}") int attempts) {
        super(parser);
        this.gateway = gateway;
        this.aiWait = turnTimeout.multipliedBy(attempts).plusSeconds(30);
        this.conversation = new AiConversationFlow(gateway, inputs, aiWait);
    }

    @Override
    public NodeType type() {
        return NodeType.AI;
    }

    @Override
    public List<String> outputs(FlowNode node, ValidationContext ctx) {
        AiNodeSettings s = settings(node, AiNodeSettings.class);
        List<String> out = new ArrayList<>();
        for (AiOutcome o : s.outcomesOrEmpty()) if (o != null && !blank(o.id())) out.add(Outputs.outcomeOf(o.id()));
        if (s.modeOrDefault() == Mode.SINGLE) {
            if (out.isEmpty()) out.add(Outputs.NEXT);
        } else {
            if (!s.exitWordsOrEmpty().isEmpty()) out.add(Outputs.EXIT_WORD);
            if (s.exitCondition() != null) out.add(Outputs.EXIT_CONDITION);
            out.add(Outputs.MAX_TURNS);
        }
        out.add(Outputs.FAILURE);
        return out;
    }

    @Override
    public Set<TaskKind> waitsFor(FlowNode node, Flow flow) {
        AiNodeSettings s = settings(node, AiNodeSettings.class);
        return s.modeOrDefault() == Mode.SINGLE ? Set.of(TaskKind.AI_RESPONSE)
                : Set.of(TaskKind.WHATSAPP_REPLY, TaskKind.AI_RESPONSE);
    }

    // ---- validation ----

    @Override
    public List<Problem> validate(FlowNode node, Flow flow, ValidationContext ctx) {
        AiNodeSettings s = settings(node, AiNodeSettings.class);
        Problems p = new Problems(node);
        boolean single = s.modeOrDefault() == Mode.SINGLE;
        AiProfileInfo profile = null;
        if (p.text("profileId", s.profileId(), 100, true)) {
            profile = gateway.profile(ctx.workspaceId(), s.profileId()).orElse(null);
            if (profile == null) p.error("profileId", "the AI profile no longer exists");
        }
        overrides(s.overrides(), profile == null ? null : profile.capabilities(), p);
        p.text("extraInstructions", s.extraInstructions(), MAX_INSTRUCTIONS, false);
        p.text("userMessage", s.userMessage(), MAX_TEXT, single);
        p.text("initialMessage", s.initialMessage(), MAX_TEXT, false);
        outcomes(s.outcomesOrEmpty(), p);
        collect(s.collectOrEmpty(), p);
        replyFormat(s, p);
        exitWords(s.exitWordsOrEmpty(), p);
        if (s.exitCondition() != null) ConditionExecutor.walk(s.exitCondition(), "exitCondition", p);
        if (s.maxTurns() != null && (s.maxTurns() < 1 || s.maxTurns() > 100)) p.error("maxTurns", "must be 1 to 100");
        if (!blank(s.continueFrom())) continueFrom(node, flow, s, profile, ctx, p);
        if (!single && s.outcomesOrEmpty().isEmpty() && s.exitWordsOrEmpty().isEmpty() && s.exitCondition() == null) {
            p.warning("outcomes", "no outcome, exit word or exit condition: only the turn limit or the customer's "
                    + "silence ends this conversation");
        }
        return p.list;
    }

    private static void overrides(AiOverrides o, AiCapabilities caps, Problems p) {
        if (o == null) return;
        p.text("overrides.model", o.model(), 200, false);
        p.text("overrides.instructions", o.instructions(), MAX_INSTRUCTIONS, false);
        if (o.maxOutputTokens() != null && (o.maxOutputTokens() < 1 || o.maxOutputTokens() > 128000)) {
            p.error("overrides.maxOutputTokens", "must be between 1 and 128000");
        }
        if (o.temperature() != null) {
            if (caps != null && !caps.temperature()) {
                p.error("overrides.temperature", "the profile's provider does not take a temperature");
            } else if (o.temperature().isNaN() || o.temperature() < 0 || o.temperature() > 2) {
                p.error("overrides.temperature", "must be between 0 and 2");
            }
        }
        if (o.reasoningEffort() != null) {
            if (caps != null && !caps.reasoningEffort()) {
                p.error("overrides.reasoningEffort", "the profile's provider does not take a reasoning effort");
            } else if (!EFFORTS.contains(o.reasoningEffort())) {
                p.error("overrides.reasoningEffort", "must be one of minimal, low, medium, high");
            }
        }
    }

    private static void outcomes(List<AiOutcome> outcomes, Problems p) {
        if (outcomes.size() > MAX_LIST) p.error("outcomes", "at most " + MAX_LIST + " outcomes");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < outcomes.size(); i++) {
            AiOutcome o = outcomes.get(i);
            String at = "outcomes." + i;
            if (o == null || blank(o.id())) {
                p.error(at + ".id", "is required");
                continue;
            }
            if (!OUTCOME_ID.matcher(o.id()).matches()) {
                p.error(at + ".id", "must be 1 to 30 lower-case letters, digits or underscores");
            } else if (!seen.add(o.id())) {
                p.error(at + ".id", "another outcome has this id");
            }
            p.text(at + ".label", o.label(), 40, false);
            p.text(at + ".description", o.description(), 1000, false);
        }
    }

    private static void collect(List<AiField> fields, Problems p) {
        if (fields.size() > MAX_LIST) p.error("collect", "at most " + MAX_LIST + " fields");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < fields.size(); i++) {
            AiField f = fields.get(i);
            String at = "collect." + i;
            if (f == null || blank(f.key())) {
                p.error(at + ".key", "is required");
                continue;
            }
            if (!COLLECT_KEY.matcher(f.key()).matches()) {
                p.error(at + ".key", "must be up to 40 letters, digits or underscores and not start with a digit");
            } else if (!seen.add(f.key())) {
                p.error(at + ".key", "another field has this key");
            }
            p.text(at + ".description", f.description(), 1000, false);
        }
    }

    private static void replyFormat(AiNodeSettings s, Problems p) {
        AiReplyFormat f = s.replyFormat();
        if (f == null || f.type() != AiReplyFormat.Type.JSON) return;
        if (f.schema() == null || !(f.schema().get("properties") instanceof Map<?, ?> props)) {
            p.error("replyFormat.schema", "a JSON reply needs a schema with properties");
            return;
        }
        if (!s.sends()) return;
        if (blank(f.messageField())) {
            p.error("replyFormat.messageField", "name the string property whose value the customer gets");
        } else if (!(props.get(f.messageField()) instanceof Map<?, ?> prop) || !isString(prop.get("type"))) {
            p.error("replyFormat.messageField", "must be a string property of the schema");
        }
    }

    private static boolean isString(Object type) {
        return "string".equals(type) || type instanceof List<?> l && l.contains("string");
    }

    private static void exitWords(List<String> words, Problems p) {
        if (words.size() > MAX_LIST) p.error("exitWords", "at most " + MAX_LIST + " exit words");
        for (int i = 0; i < words.size(); i++) p.text("exitWords." + i, words.get(i), 100, true);
    }

    /** The named node must be an AI node from which this one can be reached, on the same provider. */
    private void continueFrom(FlowNode node, Flow flow, AiNodeSettings s, AiProfileInfo profile, ValidationContext ctx,
            Problems p) {
        Optional<FlowNode> from = flow.nodes().stream()
                .filter(n -> s.continueFrom().equals(n.name()) && n.type() == NodeType.AI && !n.id().equals(node.id()))
                .findFirst();
        if (from.isEmpty() || !ancestors(flow, node.id()).contains(from.get().id())) {
            p.error("continueFrom", "must name an earlier AI node");
            return;
        }
        if (profile == null) return;
        try {
            AiNodeSettings earlier = settings(from.get(), AiNodeSettings.class);
            if (blank(earlier.profileId())) return;
            gateway.profile(ctx.workspaceId(), earlier.profileId()).ifPresent(e -> {
                if (!e.provider().equals(profile.provider())) {
                    p.error("continueFrom", "continues a conversation of another AI provider");
                }
            });
        } catch (ValidationException e) {
            // the earlier node reports its own settings
        }
    }

    private static Set<String> ancestors(Flow flow, String nodeId) {
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(List.of(nodeId));
        while (!queue.isEmpty()) {
            String id = queue.poll();
            for (FlowEdge e : flow.edges()) {
                if (e.target().equals(id) && seen.add(e.source())) queue.add(e.source());
            }
        }
        return seen;
    }

    // ---- running ----

    @Override
    public StepResult run(RunContext ctx, FlowNode node) {
        AiNodeSettings s = settings(node, AiNodeSettings.class);
        // a send retry: the message was already bought, so send the rest of it instead of asking the AI again
        Map<?, ?> pending = AiPendingSend.of(ctx, node);
        if (pending != null) {
            AiPendingSend.deliver(ctx, node);
            Object then = pending.get("then");
            if (AiPendingSend.NEXT.equals(then)) return AiPendingSend.storedNext(pending);
            if (AiPendingSend.CONTINUE.equals(then)) return conversation.afterReply(ctx, node, s);
            if (AiPendingSend.START.equals(then)) return conversation.begin(ctx, node, s);
            throw new IllegalStateException("unknown continuation of an unsent AI message: " + then);
        }
        if (s.modeOrDefault() == Mode.CONVERSATION) return conversation.start(ctx, node, s);
        String conversationId = continued(ctx, s);
        List<AiOutcome> outcomes = s.outcomesOrEmpty();
        TurnRequest request = new TurnRequest(ctx.workspaceId(), ctx.run().getId(), ctx.run().getChatbotId(),
                node.name(), s.profileId(), s.overrides(), blank(s.extraInstructions()) ? null : ctx.render(s.extraInstructions()),
                List.of(ctx.render(s.userMessage())), outcomes, s.collectOrEmpty(), !outcomes.isEmpty(), s.replyFormat(),
                conversationId, null);
        String callId;
        try {
            callId = gateway.submit(request);
        } catch (ValidationException | NotFoundException | CodedException e) {
            // the profile or its connection went away since the flow was published
            return failure("NOT_SUBMITTED", 0, usage(0, 0), conversationId);
        }
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("conversationId", conversationId);
        state.put("turns", 0);
        state.put("lastCallId", callId);
        state.put("supersedes", 0);
        state.put("consumedUntil", null);
        state.put("startedAt", ctx.now().toString());
        state.put("lastReply", null);
        state.put("usage", usage(0, 0));
        ctx.vars().put(node.name(), state);
        return StepResult.wait(Waiting.ai(callId, ctx.now(), ctx.now().plus(aiWait)),
                "Waiting for the AI (turn 1, call " + callId + ")");
    }

    @Override
    public StepResult onAnswer(RunContext ctx, FlowNode node, TaskAnswer answer) {
        AiNodeSettings s = settings(node, AiNodeSettings.class);
        boolean conversing = s.modeOrDefault() == Mode.CONVERSATION;
        if (conversing && answer.kind() == TaskKind.WHATSAPP_REPLY) return conversation.onCustomer(ctx, node, s);
        if (answer.kind() != TaskKind.AI_RESPONSE) throw new IllegalStateException("the AI node got a " + answer.kind());
        TurnResult r = (TurnResult) answer.payload().get("result");
        if (conversing) return conversation.onAi(ctx, node, s, r);
        Map<String, Object> prev = state(ctx, node);
        Map<String, Object> usage = addUsage(prev, r.usage());
        String conversationId = r.conversationId() != null ? r.conversationId() : (String) prev.get("conversationId");
        int turns = 1;
        if (r.status() != TurnStatus.DONE) {
            return failure(blank(r.errorCode()) ? "UNKNOWN" : r.errorCode(), turns, usage, conversationId);
        }
        AiFinish finish = r.finish();
        String outcome = finish == null ? null : finish.outcome();
        List<AiOutcome> outcomes = s.outcomesOrEmpty();
        Optional<AiOutcome> chosen = outcomes.stream().filter(o -> o != null && o.id() != null && o.id().equals(outcome)).findFirst();
        if (!outcomes.isEmpty() && chosen.isEmpty()) return failure("UNKNOWN_OUTCOME", turns, usage, conversationId);
        boolean ownReply = finish != null && !blank(finish.reply());
        String reply = ownReply ? finish.reply() : blank(r.text()) ? null : r.text();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reply", reply);
        result.put("json", r.json());
        result.put("outcome", outcome);
        result.put("fields", finish == null || finish.fields() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(finish.fields()));
        result.put("turns", turns);
        result.put("usage", usage);
        result.put("conversationId", conversationId);

        List<String> parts = !s.sends() ? List.of()
                : WhatsAppFormat.convert(ownReply ? finish.reply() : customerText(s, r.text(), r.json()));
        String summary = chosen.map(o -> "AI chose “" + (blank(o.label()) ? o.id() : o.label()) + "”").orElse("AI replied")
                + (parts.isEmpty() ? "" : " and the reply was sent");
        StepResult then = StepResult.next(chosen.map(o -> Outputs.outcomeOf(o.id())).orElse(Outputs.NEXT), result, summary);
        if (!parts.isEmpty()) AiPendingSend.send(ctx, node, parts, AiPendingSend.NEXT, then);
        return then;
    }

    // ---- shared with the conversation flow ----

    /** The text the customer gets of a turn: the JSON format's message field, else the text. */
    static String customerText(AiNodeSettings s, String text, Map<String, Object> json) {
        AiReplyFormat f = s.replyFormat();
        if (f != null && f.type() == AiReplyFormat.Type.JSON) {
            Object v = json == null || f.messageField() == null ? null : json.get(f.messageField());
            return v == null ? null : String.valueOf(v);
        }
        return text;
    }

    /** Only the code, never the provider's own wording (it may quote a key or an internal detail). */
    static StepResult failure(String errorCode, int turns, Map<String, Object> usage, String conversationId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("errorCode", errorCode);
        result.put("turns", turns);
        result.put("usage", usage);
        result.put("conversationId", conversationId);
        return StepResult.next(Outputs.FAILURE, result, "AI failed: " + errorCode);
    }

    /** The conversation of the {@code continueFrom} node, from its variables; null for a new one. */
    static String continued(RunContext ctx, AiNodeSettings s) {
        if (blank(s.continueFrom())) return null;
        Object v = ctx.vars().get(s.continueFrom());
        return v instanceof Map<?, ?> m && m.get("conversationId") instanceof String id && !id.isBlank() ? id : null;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> state(RunContext ctx, FlowNode node) {
        Object v = ctx.vars().get(node.name());
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> prevUsage(Map<String, Object> state) {
        return state.get("usage") instanceof Map<?, ?> m ? (Map<String, Object>) m : usage(0, 0);
    }

    /** The stored usage plus this turn's. */
    static Map<String, Object> addUsage(Map<String, Object> state, AiUsage u) {
        AiUsage add = u == null ? new AiUsage(0, 0, 0, 0) : u;
        Map<String, Object> prev = prevUsage(state);
        return usage(number(prev.get("inputTokens")) + add.inputTokens(), number(prev.get("outputTokens")) + add.outputTokens());
    }

    static long number(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }

    static Map<String, Object> usage(long in, long out) {
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("inputTokens", in);
        u.put("outputTokens", out);
        return u;
    }
}
