package me.nageshk.omnichannel.service.chatbot.node;

/** Where a flow is validated: the brand, the chatbot being saved (for Jump-to-self), and whether for a test. */
public record ValidationContext(String workspaceId, String chatbotId, boolean test) {
}
