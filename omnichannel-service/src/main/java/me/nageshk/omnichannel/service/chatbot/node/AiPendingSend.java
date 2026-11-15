package me.nageshk.omnichannel.service.chatbot.node;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.service.chatbot.flow.FlowNode;
import me.nageshk.omnichannel.service.chatbot.run.ChatbotSender;

/**
 * A message of the AI node that has not fully reached the customer yet, kept in the node state as
 * {@value #KEY}: the parts, how many went out, and how the node goes on once all did. It is written before the first
 * part is sent, so when a send must be retried (no device, WhatsApp Web not ready) the runner saves the run with it,
 * and its retry calls the node's {@code run}, which resends from the first unsent part instead of buying a new AI
 * turn. A failed resend throws again with no step done in between, so the runner's send-attempt cap ends the run as
 * for any node.
 */
final class AiPendingSend {

    static final String KEY = "pendingSend";

    /** Then go on through the stored output, result and summary. */
    static final String NEXT = "NEXT";
    /** Conversation: an AI reply went out; the exit checks follow, then the customer's turn. */
    static final String CONTINUE = "CONTINUE";
    /** Conversation: the initial message went out; the first turn or the first wait follows. */
    static final String START = "START";

    private AiPendingSend() {}

    /** Keeps {@code parts} and the way on ({@code next} for {@link #NEXT}), then sends them all. */
    static void send(RunContext ctx, FlowNode node, List<String> parts, String then, StepResult next) {
        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("parts", new ArrayList<>(parts));
        pending.put("sent", 0);
        pending.put("then", then);
        if (next != null) {
            pending.put("output", next.output());
            pending.put("result", next.result());
            pending.put("summary", next.summary());
        }
        put(ctx, node, pending);
        deliver(ctx, node);
    }

    /** The unsent message of this node, or null. */
    static Map<?, ?> of(RunContext ctx, FlowNode node) {
        return AiExecutor.state(ctx, node).get(KEY) instanceof Map<?, ?> m ? m : null;
    }

    /** Sends the parts not sent yet, counting each one that went out, and drops the marker once all did. */
    static void deliver(RunContext ctx, FlowNode node) {
        Map<?, ?> pending = of(ctx, node);
        if (pending == null) return;
        List<?> parts = pending.get("parts") instanceof List<?> l ? l : List.of();
        int sent = (int) AiExecutor.number(pending.get("sent"));
        ChatbotSender sender = ctx.sender(ChatbotSender.class);
        for (int i = sent; i < parts.size(); i++) {
            sender.send(ctx, SendMessageExecutor.textMessage(String.valueOf(parts.get(i))));
            Map<String, Object> next = new LinkedHashMap<>();
            pending.forEach((k, v) -> next.put(String.valueOf(k), v));
            next.put("sent", i + 1);
            put(ctx, node, next);
            pending = next;
        }
        Map<String, Object> state = new LinkedHashMap<>(AiExecutor.state(ctx, node));
        state.remove(KEY);
        ctx.vars().put(node.name(), state);
    }

    /** The NEXT a message was kept with. */
    @SuppressWarnings("unchecked")
    static StepResult storedNext(Map<?, ?> pending) {
        Object result = pending.get("result");
        return StepResult.next((String) pending.get("output"),
                result instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : null,
                (String) pending.get("summary"));
    }

    private static void put(RunContext ctx, FlowNode node, Map<String, Object> pending) {
        Map<String, Object> state = new LinkedHashMap<>(AiExecutor.state(ctx, node));
        state.put(KEY, pending);
        ctx.vars().put(node.name(), state);
    }
}
