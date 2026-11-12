package me.nageshk.omnichannel.service.chatbot.flow;

import tools.jackson.databind.JsonNode;

/** A node as the server reads it: the canvas id, the person's name for it, its type and its raw settings. */
public record FlowNode(String id, String name, NodeType type, JsonNode settings) {
}
