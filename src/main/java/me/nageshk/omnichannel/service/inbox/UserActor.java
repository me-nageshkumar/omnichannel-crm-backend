package me.nageshk.omnichannel.service.inbox;

import me.nageshk.omnichannel.db.inbox.MessageSource;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;

/** A console user sending a reply; {@code displayName} is the user's stored name. */
public record UserActor(AuthenticatedUser me, String displayName) implements SendActor {

    @Override
    public String userId() {
        return me.userId();
    }

    @Override
    public MessageSource source() {
        return MessageSource.CONSOLE;
    }

    @Override
    public String campaignId() {
        return null;
    }
}
