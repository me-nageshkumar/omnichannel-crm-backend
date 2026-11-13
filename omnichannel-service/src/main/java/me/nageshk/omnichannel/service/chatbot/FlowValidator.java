package me.nageshk.omnichannel.service.chatbot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.db.chatbot.TaskKind;
import me.nageshk.omnichannel.service.chatbot.flow.Flow;
import me.nageshk.omnichannel.service.chatbot.flow.FlowEdge;
import me.nageshk.omnichannel.service.chatbot.flow.FlowNode;
import me.nageshk.omnichannel.service.chatbot.flow.NodeType;
import me.nageshk.omnichannel.service.chatbot.node.NodeExecutor;
import me.nageshk.omnichannel.service.chatbot.node.NodeExecutors;
import me.nageshk.omnichannel.service.chatbot.node.Problem;
import me.nageshk.omnichannel.service.chatbot.node.Severity;
import me.nageshk.omnichannel.service.chatbot.node.ValidationContext;
import me.nageshk.omnichannel.service.chatbot.run.ChatbotText;
import me.nageshk.omnichannel.service.chatbot.run.TaskAnswerSources;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The flow-level checks of spec §6 (names, edges, the entry node, reachability, variable paths, Jump to self, waits
 * something can answer), then every node's own executor checks. Problems carry a severity; publishing needs no ERROR.
 */
@Component
public class FlowValidator {

    public static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,40}");
    /** Variable roots that are not node names (spec §4.4; {@code form} inside an Ask with a form's link message). */
    static final Set<String> ROOTS = Set.of("contact", "chat", "number", "event", "trigger", "now", "form");

    private final NodeExecutors executors;
    private final TaskAnswerSources sources;

    public FlowValidator(NodeExecutors executors, TaskAnswerSources sources) {
        this.executors = executors;
        this.sources = sources;
    }

    public List<Problem> validate(Flow flow, ValidationContext ctx) {
        List<Problem> out = new ArrayList<>();
        if (flow.nodes().isEmpty()) {
            out.add(Problem.flow("nodes", "add at least one node"));
            return out;
        }
        names(flow, out);
        edges(flow, ctx, out);
        entry(flow, out);
        Set<String> names = new HashSet<>();
        for (FlowNode n : flow.nodes()) if (n.name() != null) names.add(n.name());
        Set<TaskKind> answered = sources.kinds();
        for (FlowNode n : flow.nodes()) {
            NodeExecutor e = executors.of(n.type());
            variables(n, names, out);
            if (n.type() == NodeType.JUMP && n.settings() != null && ctx.chatbotId() != null
                    && ctx.chatbotId().equals(text(n.settings().get("chatbotId")))) {
                out.add(Problem.error(n, "settings.chatbotId", "a chatbot cannot jump to itself"));
            }
            try {
                for (TaskKind k : e.waitsFor(n, flow)) {
                    if (!answered.contains(k)) out.add(Problem.error(n, "settings", "waits for " + k + ", which nothing answers"));
                }
                out.addAll(e.validate(n, flow, ctx));
            } catch (ValidationException ex) {
                ex.fields().forEach((f, m) -> out.add(Problem.error(n, f.startsWith("nodes.") ? "settings" : f, m)));
            }
        }
        return out;
    }

    public static boolean publishable(List<Problem> problems) {
        return problems.stream().noneMatch(p -> p.severity() == Severity.ERROR);
    }

    private static void names(Flow flow, List<Problem> out) {
        Set<String> seen = new HashSet<>();
        for (FlowNode n : flow.nodes()) {
            if (n.name() == null || !NAME.matcher(n.name()).matches()) {
                out.add(Problem.error(n, "name", "must be 1 to 40 letters, digits or underscores"));
                continue;
            }
            if (!seen.add(n.name().toLowerCase(Locale.ROOT))) out.add(Problem.error(n, "name", "another node has this name"));
        }
    }

    private void edges(Flow flow, ValidationContext ctx, List<Problem> out) {
        Map<String, List<String>> outputs = new HashMap<>();
        for (FlowNode n : flow.nodes()) {
            try {
                outputs.put(n.id(), executors.of(n.type()).outputs(n, ctx));
            } catch (ValidationException e) {
                outputs.put(n.id(), List.of());
            }
        }
        Set<String> used = new HashSet<>();
        for (FlowEdge e : flow.edges()) {
            FlowNode source = flow.node(e.source()).orElseThrow();
            String edgeField = "edges." + (e.id() == null ? e.source() + ">" + e.target() : e.id());
            if (!outputs.get(source.id()).contains(e.output())) {
                out.add(Problem.error(source, edgeField, "leaves an output the node does not have (" + e.output() + ")"));
            } else if (!used.add(source.id() + "\u0000" + e.output())) {
                out.add(Problem.error(source, edgeField, "a second edge leaves the output " + e.output()));
            }
        }
    }

    private static void entry(Flow flow, List<Problem> out) {
        Set<String> roots = flow.entryCandidates();
        if (roots.isEmpty()) {
            out.add(Problem.flow("entry", "no entry node: every node has an edge pointing to it"));
            return;
        }
        if (roots.size() > 1) {
            out.add(Problem.flow("entry", "two or more entry nodes: only one node may have nothing pointing to it ("
                    + String.join(", ", roots) + ")"));
            return;
        }
        String entry = roots.iterator().next();
        Set<String> reached = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(entry);
        while (!queue.isEmpty()) {
            String id = queue.poll();
            if (!reached.add(id)) continue;
            for (FlowEdge e : flow.from(id)) queue.add(e.target());
        }
        for (FlowNode n : flow.nodes()) {
            if (!reached.contains(n.id())) out.add(Problem.warning(n, "node", "is not reachable from the entry node and never runs"));
        }
    }

    /** Every string in the settings: a {@code {{root.x}}} whose root is neither a known root nor a node is a WARNING. */
    private static void variables(FlowNode n, Set<String> names, List<Problem> out) {
        if (n.settings() == null) return;
        walk(n.settings(), "settings", (field, text) -> {
            for (String path : ChatbotText.paths(text)) {
                String root = path.contains(".") ? path.substring(0, path.indexOf('.')) : path;
                if (!ROOTS.contains(root) && !names.contains(root)) {
                    out.add(Problem.warning(n, field, "names an unknown variable " + path));
                }
            }
        });
    }

    private static void walk(JsonNode node, String path, java.util.function.BiConsumer<String, String> onText) {
        if (node == null) return;
        if (node.isString()) {
            onText.accept(path, node.asString());
        } else if (node.isObject()) {
            node.properties().forEach(e -> walk(e.getValue(), path + "." + e.getKey(), onText));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) walk(node.get(i), path + "[" + i + "]", onText);
        }
    }

    private static String text(JsonNode n) {
        return n == null || n.isNull() ? null : n.asString();
    }
}
