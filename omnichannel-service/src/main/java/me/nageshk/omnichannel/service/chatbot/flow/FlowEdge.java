package me.nageshk.omnichannel.service.chatbot.flow;

/** An edge from a node's {@code output} (the canvas's {@code sourceHandle}, {@code next} when absent) to a node. */
public record FlowEdge(String id, String source, String output, String target) {
}
