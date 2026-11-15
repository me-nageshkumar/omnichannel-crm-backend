package me.nageshk.omnichannel.service.chatbot.node;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import me.nageshk.omnichannel.ai.api.AiFinish;
import me.nageshk.omnichannel.ai.api.AiGateway;
import me.nageshk.omnichannel.ai.api.AiOutcome;
import me.nageshk.omnichannel.ai.api.TurnRequest;
import me.nageshk.omnichannel.ai.api.TurnResult;
import me.nageshk.omnichannel.ai.api.TurnStatus;
import me.nageshk.omnichannel.common.api.CodedException;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.db.chatbot.Waiting;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.service.automation.ConditionEvaluator;
import me.nageshk.omnichannel.service.chatbot.flow.FlowNode;
import me.nageshk.omnichannel.service.chatbot.flow.Outputs;
import me.nageshk.omnichannel.service.chatbot.flow.settings.AiNodeSettings;
import me.nageshk.omnichannel.service.chatbot.run.NodeFailed;
import me.nageshk.omnichannel.service.chatbot.run.RunVariables;

/**
 * The AI node in conversation mode (spec §5.9): the bot and the customer talk over many messages, on one run step,
 * until the AI finishes with an outcome, the customer writes an exit word, the exit condition holds after a reply, or
 * the turn limit is reached. Silence is the run's reminder and inactivity end; an agent reply is the hand-over.
 *
 * <p>The inbox is the queue (ruling R1): the node state keeps {@code consumedUntil}/{@code consumedId}, the last
 * customer message given to the AI, and {@link AiInputs#unconsumed} reads what came after, when a WhatsApp reply is
 * answered, when an AI turn comes back, and right before every wait on the customer. So a burst of messages becomes
 * one turn, and a message that lands while the AI thinks or the run is busy is never lost. A turn that comes back with
 * new messages waiting and called no tool and no finish is superseded (§5.5): its reply is not sent and a new turn
 * answers everything from the same base; at most {@value #MAX_SUPERSEDES} times in a row.
 *
 * <p>Every message to the customer goes through {@link AiPendingSend}, so a send retry resends and never asks the AI
 * again.
 */
final class AiConversationFlow {

    static final int MAX_SUPERSEDES = 2;

    private final AiGateway gateway;
    private final AiInputs inputs;
    private final Duration aiWait;

    AiConversationFlow(AiGateway gateway, AiInputs inputs, Duration aiWait) {
        this.gateway = gateway;
        this.inputs = inputs;
        this.aiWait = aiWait;
    }

    /** Fresh state; what the contact wrote before the node started is not the conversation's. */
    StepResult start(RunContext ctx, FlowNode node, AiNodeSettings s) {
        Optional<WaWebMessage> latest = inputs.latest(chatId(ctx));
        Map<String, Object> st = new LinkedHashMap<>();
        st.put("conversationId", AiExecutor.continued(ctx, s));
        st.put("turns", 0);
        st.put("lastCallId", null);
        st.put("supersedes", 0);
        st.put("consumedUntil", latest.map(m -> iso(m.getCreationTime())).orElse(null));
        st.put("consumedId", latest.map(WaWebMessage::getId).orElse(null));
        st.put("startedAt", ctx.now().toString());
        st.put("inputs", new ArrayList<>());
        st.put("lastReply", null);
        st.put("lastJson", null);
        st.put("usage", AiExecutor.usage(0, 0));
        ctx.vars().put(node.name(), st);
        if (!AiExecutor.blank(s.initialMessage())) {
            AiPendingSend.send(ctx, node, List.of(ctx.render(s.initialMessage())), AiPendingSend.START, null);
        }
        return begin(ctx, node, s);
    }

    /** After the initial message: the user message is the first turn, else the customer speaks first. */
    StepResult begin(RunContext ctx, FlowNode node, AiNodeSettings s) {
        if (!AiExecutor.blank(s.userMessage())) return submit(ctx, node, s, List.of(ctx.render(s.userMessage())), null);
        return awaitCustomer(ctx, node, s);
    }

    /** The customer wrote: everything unconsumed is the next turn (a reply that is not new is nothing to answer). */
    StepResult onCustomer(RunContext ctx, FlowNode node, AiNodeSettings s) {
        List<WaWebMessage> fresh = unconsumed(ctx, node);
        if (fresh.isEmpty()) return waitCustomer(ctx, node);
        return take(ctx, node, s, fresh, List.of(), null);
    }

    StepResult onAi(RunContext ctx, FlowNode node, AiNodeSettings s, TurnResult r) {
        Map<String, Object> st = new LinkedHashMap<>(AiExecutor.state(ctx, node));
        st.put("usage", AiExecutor.addUsage(st, r.usage()));
        if (r.conversationId() != null) st.put("conversationId", r.conversationId());
        ctx.vars().put(node.name(), st);
        int turns = (int) AiExecutor.number(st.get("turns"));
        if (r.status() != TurnStatus.DONE) {
            return AiExecutor.failure(AiExecutor.blank(r.errorCode()) ? "UNKNOWN" : r.errorCode(), turns,
                    AiExecutor.prevUsage(st), (String) st.get("conversationId"));
        }
        List<WaWebMessage> fresh = unconsumed(ctx, node);
        int supersedes = (int) AiExecutor.number(st.get("supersedes"));
        if (!fresh.isEmpty() && !r.toolsUsed() && r.finish() == null && supersedes < MAX_SUPERSEDES) {
            // the reply answers less than the customer has said by now: answer it all instead, from the same base
            st.put("supersedes", supersedes + 1);
            return take(ctx, node, s, fresh, inputsOf(st), r.callId());
        }
        turns++;
        st.put("turns", turns);
        st.put("supersedes", 0);
        if (r.finish() != null) return finish(ctx, node, s, r, st, fresh);
        st.put("lastReply", AiExecutor.blank(r.text()) ? null : r.text());
        st.put("lastJson", r.json());
        List<String> parts = WhatsAppFormat.convert(AiExecutor.customerText(s, r.text(), r.json()));
        if (!parts.isEmpty()) AiPendingSend.send(ctx, node, parts, AiPendingSend.CONTINUE, null);
        return afterReply(ctx, node, s);
    }

    /** The finish reply, else the turn's text, else nothing goes out; the outcome is taken either way. */
    private StepResult finish(RunContext ctx, FlowNode node, AiNodeSettings s, TurnResult r, Map<String, Object> st,
            List<WaWebMessage> fresh) {
        AiFinish f = r.finish();
        List<AiOutcome> outcomes = s.outcomesOrEmpty();
        Optional<AiOutcome> chosen = outcomes.stream()
                .filter(o -> o != null && o.id() != null && o.id().equals(f.outcome())).findFirst();
        int turns = (int) AiExecutor.number(st.get("turns"));
        if (chosen.isEmpty()) {
            return AiExecutor.failure("UNKNOWN_OUTCOME", turns, AiExecutor.prevUsage(st), (String) st.get("conversationId"));
        }
        boolean ownReply = !AiExecutor.blank(f.reply());
        String reply = ownReply ? f.reply() : AiExecutor.blank(r.text()) ? null : r.text();
        Map<String, Object> result = result(st);
        result.put("reply", reply);
        result.put("json", r.json());
        result.put("outcome", f.outcome());
        result.put("fields", f.fields() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(f.fields()));
        // what the customer said meanwhile is not the AI's any more: the inbox keeps it for whatever comes next
        if (!fresh.isEmpty()) result.put("pendingAfterFinish", fresh.stream().map(WaWebMessage::getId).toList());
        AiOutcome o = chosen.get();
        String summary = "Turn " + turns + ": AI chose “" + (AiExecutor.blank(o.label()) ? o.id() : o.label())
                + "” (call " + r.callId() + ")";
        StepResult then = StepResult.next(Outputs.outcomeOf(o.id()), result, summary);
        List<String> parts = WhatsAppFormat.convert(ownReply ? f.reply() : AiExecutor.customerText(s, r.text(), r.json()));
        if (!parts.isEmpty()) AiPendingSend.send(ctx, node, parts, AiPendingSend.NEXT, then);
        return then;
    }

    /** After a reply went out: the exit condition, then the turn limit, then the customer's turn. */
    StepResult afterReply(RunContext ctx, FlowNode node, AiNodeSettings s) {
        Map<String, Object> st = AiExecutor.state(ctx, node);
        int turns = (int) AiExecutor.number(st.get("turns"));
        Map<String, Object> result = result(st);
        if (s.exitCondition() != null && exitConditionMet(ctx, node, s, result)) {
            return StepResult.next(Outputs.EXIT_CONDITION, result, "Turn " + turns + ": the exit condition was met");
        }
        if (turns >= s.maxTurnsOrDefault()) {
            return StepResult.next(Outputs.MAX_TURNS, result, "Turn " + turns + ": the turn limit was reached");
        }
        return awaitCustomer(ctx, node, s);
    }

    /** Before waiting on the customer: anything they wrote meanwhile is answered now. */
    private StepResult awaitCustomer(RunContext ctx, FlowNode node, AiNodeSettings s) {
        List<WaWebMessage> fresh = unconsumed(ctx, node);
        if (!fresh.isEmpty()) return take(ctx, node, s, fresh, List.of(), null);
        return waitCustomer(ctx, node);
    }

    private StepResult waitCustomer(RunContext ctx, FlowNode node) {
        Map<String, Object> st = AiExecutor.state(ctx, node);
        int turns = (int) AiExecutor.number(st.get("turns"));
        String summary = turns == 0 ? "Waiting for the customer's first message"
                : "Turn " + turns + " answered (call " + st.get("lastCallId") + "); waiting for the customer";
        Object reply = st.get("lastReply");
        return StepResult.wait(Waiting.whatsApp(chatId(ctx), ctx.now(), reply == null ? null : String.valueOf(reply)),
                summary);
    }

    /**
     * The customer's new messages are consumed: an exit word among them ends the node without calling the AI;
     * otherwise they (after {@code prior}, the inputs of a superseded turn) are the next turn.
     */
    private StepResult take(RunContext ctx, FlowNode node, AiNodeSettings s, List<WaWebMessage> fresh,
            List<String> prior, String supersedesCallId) {
        Map<String, Object> st = new LinkedHashMap<>(AiExecutor.state(ctx, node));
        WaWebMessage last = fresh.get(fresh.size() - 1);
        st.put("consumedUntil", iso(last.getCreationTime()));
        st.put("consumedId", last.getId());
        ctx.vars().put(node.name(), st);
        Optional<String> word = exitWord(s, fresh);
        if (word.isPresent()) {
            Map<String, Object> result = result(st);
            result.put("exitWord", word.get());
            return StepResult.next(Outputs.EXIT_WORD, result,
                    "Turn " + AiExecutor.number(st.get("turns")) + ": the customer wrote “" + word.get() + "”");
        }
        List<String> in = new ArrayList<>(prior);
        for (WaWebMessage m : fresh) {
            String t = AiInputs.text(m);
            if (!t.isBlank()) in.add(t);
        }
        if (in.isEmpty()) return waitCustomer(ctx, node);
        return submit(ctx, node, s, in, supersedesCallId);
    }

    private StepResult submit(RunContext ctx, FlowNode node, AiNodeSettings s, List<String> in, String supersedesCallId) {
        Map<String, Object> st = new LinkedHashMap<>(AiExecutor.state(ctx, node));
        String conversationId = (String) st.get("conversationId");
        TurnRequest request = new TurnRequest(ctx.workspaceId(), ctx.run().getId(), ctx.run().getChatbotId(),
                node.name(), s.profileId(), s.overrides(),
                AiExecutor.blank(s.extraInstructions()) ? null : ctx.render(s.extraInstructions()), List.copyOf(in),
                s.outcomesOrEmpty(), s.collectOrEmpty(), false, s.replyFormat(), conversationId, supersedesCallId);
        String callId;
        try {
            callId = gateway.submit(request);
        } catch (ValidationException | NotFoundException | CodedException e) {
            return AiExecutor.failure("NOT_SUBMITTED", (int) AiExecutor.number(st.get("turns")), AiExecutor.prevUsage(st),
                    conversationId);
        }
        st.put("lastCallId", callId);
        st.put("inputs", new ArrayList<>(in));
        ctx.vars().put(node.name(), st);
        long turn = AiExecutor.number(st.get("turns")) + 1;
        return StepResult.wait(Waiting.ai(callId, ctx.now(), ctx.now().plus(aiWait)), "Turn " + turn
                + ": waiting for the AI (call " + callId + ")" + (supersedesCallId == null ? "" : ", the customer wrote again"));
    }

    // ---- checks ----

    /** The configured word the customer wrote, case and spacing ignored. */
    static Optional<String> exitWord(AiNodeSettings s, List<WaWebMessage> messages) {
        for (WaWebMessage m : messages) {
            String said = normal(AiInputs.text(m));
            for (String w : s.exitWordsOrEmpty()) {
                if (w != null && !w.isBlank() && normal(w).equals(said)) return Optional.of(w.strip());
            }
        }
        return Optional.empty();
    }

    static String normal(String text) {
        return text.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** The exit condition over the run's variables, with this node's current result under its name. */
    private static boolean exitConditionMet(RunContext ctx, FlowNode node, AiNodeSettings s, Map<String, Object> result) {
        Map<String, Object> vars = new LinkedHashMap<>(ctx.vars());
        vars.put(node.name(), result);
        return ConditionEvaluator.matches(ConditionExecutor.normalise(s.exitCondition(), ctx), RunVariables.flatten(vars));
    }

    // ---- state ----

    private List<WaWebMessage> unconsumed(RunContext ctx, FlowNode node) {
        Map<String, Object> st = AiExecutor.state(ctx, node);
        return inputs.unconsumed(chatId(ctx), instant(st.get("consumedUntil")), (String) st.get("consumedId"),
                instant(st.get("startedAt")));
    }

    /** The node's result as the conversation stands: the last reply and JSON, the turns, the usage. */
    private static Map<String, Object> result(Map<String, Object> st) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reply", st.get("lastReply"));
        result.put("json", st.get("lastJson"));
        result.put("outcome", null);
        result.put("fields", new LinkedHashMap<>());
        result.put("turns", (int) AiExecutor.number(st.get("turns")));
        result.put("usage", AiExecutor.prevUsage(st));
        result.put("conversationId", st.get("conversationId"));
        return result;
    }

    private static List<String> inputsOf(Map<String, Object> st) {
        List<String> out = new ArrayList<>();
        if (st.get("inputs") instanceof List<?> l) for (Object o : l) if (o != null) out.add(String.valueOf(o));
        return out;
    }

    private static String chatId(RunContext ctx) {
        if (ctx.chat() == null) throw new NodeFailed("an AI conversation needs a WhatsApp chat");
        return ctx.chat().getId();
    }

    private static String iso(Instant at) {
        return at == null ? null : at.toString();
    }

    private static Instant instant(Object v) {
        if (v instanceof String str && !str.isBlank()) return Instant.parse(str);
        if (v instanceof Date d) return d.toInstant();
        if (v instanceof Instant i) return i;
        return null;
    }
}
