package me.nageshk.omnichannel.service.chatbot.flow;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** The parsed graph (spec §4.1): nodes and edges, with the lookups the runner and the validator need. */
public record Flow(List<FlowNode> nodes, List<FlowEdge> edges) {

    public Optional<FlowNode> node(String id) {
        return nodes.stream().filter(n -> n.id().equals(id)).findFirst();
    }

    public List<FlowEdge> from(String nodeId) {
        return edges.stream().filter(e -> e.source().equals(nodeId)).toList();
    }

    /** Where the edge from {@code output} of {@code nodeId} leads, if any. */
    public Optional<String> target(String nodeId, String output) {
        return edges.stream().filter(e -> e.source().equals(nodeId) && e.output().equals(output))
                .map(FlowEdge::target).findFirst();
    }

    /** Nodes with no incoming edge, in node order; exactly one of them is the entry (spec §4.3). */
    public Set<String> entryCandidates() {
        Set<String> targets = new LinkedHashSet<>();
        for (FlowEdge e : edges) targets.add(e.target());
        Set<String> out = new LinkedHashSet<>();
        for (FlowNode n : nodes) if (!targets.contains(n.id())) out.add(n.id());
        return out;
    }
}
