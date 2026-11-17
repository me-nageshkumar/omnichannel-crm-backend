package me.nageshk.omnichannel.service.chatbot.node;

/**
 * Where a flow is validated: the brand, the chatbot being saved (for Jump-to-self), whether for a test, and the
 * most nodes and AI nodes the customer's plan allows in one chatbot (null when it sets no cap).
 */
public record ValidationContext(String workspaceId, String chatbotId, boolean test, Integer maxNodes,
        Integer maxAiNodes) {

    public ValidationContext(String workspaceId, String chatbotId, boolean test) {
        this(workspaceId, chatbotId, test, null, null);
    }
}
