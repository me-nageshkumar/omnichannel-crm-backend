package me.nageshk.omnichannel.service.page;

import me.nageshk.omnichannel.db.page.MessagePage;

/** Spring events a message page raises. */
public final class MessagePageEvents {

    /**
     * The customer did something on a page. {@code firstOfItsKind} is true for the first open and for the first
     * action with that kind and id, so listeners can count once per thing.
     */
    public record Activity(MessagePage page, MessagePage.Action action, boolean firstOfItsKind) {}

    private MessagePageEvents() {}
}
