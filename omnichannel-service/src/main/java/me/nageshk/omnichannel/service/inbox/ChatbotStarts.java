package me.nageshk.omnichannel.service.inbox;

/** Whether a message in the workspace may start a chatbot; the automation rules answer it (spec 2026-11-18 §5.2). */
public interface ChatbotStarts {

    /** True when a published message rule starts a chatbot and the customer's plan allows chatbots. */
    boolean mayStartFromMessage(String workspaceId);
}
