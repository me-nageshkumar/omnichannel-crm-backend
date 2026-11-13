package me.nageshk.omnichannel.service.inbox;

import me.nageshk.omnichannel.db.inbox.MessageSource;

/** A chatbot run sending a message: no user, source {@link MessageSource#CHATBOT}, shown as "Chatbot: <name>". */
public record ChatbotActor(String chatbotId, String chatbotName, String runId) implements SendActor {

    @Override
    public String userId() {
        return null;
    }

    @Override
    public String displayName() {
        return "Chatbot: " + chatbotName;
    }

    @Override
    public MessageSource source() {
        return MessageSource.CHATBOT;
    }

    @Override
    public String campaignId() {
        return null;
    }
}
