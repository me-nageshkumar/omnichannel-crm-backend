package me.nageshk.omnichannel.service.chatbot.node;

import me.nageshk.omnichannel.service.chatbot.flow.FlowNode;

/**
 * One validation finding (spec §6). {@code field} is relative to the node ({@code name}, {@code settings.<key>},
 * {@code edges.<edgeId>}); flow-level findings have no node and fields such as {@code entry} or {@code nodes}.
 */
public record Problem(String nodeId, String nodeName, String field, String message, Severity severity) {

    public static Problem error(FlowNode node, String field, String message) {
        return new Problem(node.id(), node.name(), field, message, Severity.ERROR);
    }

    public static Problem warning(FlowNode node, String field, String message) {
        return new Problem(node.id(), node.name(), field, message, Severity.WARNING);
    }

    public static Problem flow(String field, String message) {
        return new Problem(null, null, field, message, Severity.ERROR);
    }
}
