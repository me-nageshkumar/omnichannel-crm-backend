package me.nageshk.omnichannel.service.inbox;

import me.nageshk.omnichannel.db.inbox.MessageSource;

/**
 * An automation rule sending one of its messages: no user, shown as {@code "Automation: <rule name>"}. It may send to
 * a closed chat, and it never claims or reopens the chat.
 */
public record AutomationActor(String ruleId, String ruleName) implements SendActor {

    @Override
    public String userId() {
        return null;
    }

    @Override
    public String displayName() {
        return "Automation: " + ruleName;
    }

    @Override
    public MessageSource source() {
        return MessageSource.AUTOMATION;
    }

    @Override
    public String campaignId() {
        return null;
    }
}
