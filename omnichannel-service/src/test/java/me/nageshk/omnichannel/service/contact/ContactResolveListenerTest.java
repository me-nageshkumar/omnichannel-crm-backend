package me.nageshk.omnichannel.service.contact;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import me.nageshk.omnichannel.db.inbox.MessageDirection;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.service.inbox.InboxEvents;
import org.junit.jupiter.api.Test;

class ContactResolveListenerTest {

    private final ContactIdResolver resolver = mock(ContactIdResolver.class);
    private final ContactResolveListener listener = new ContactResolveListener(resolver);

    private static WaWebChat chat(String contactId) {
        WaWebChat c = new WaWebChat();
        c.setWorkspaceId("ws");
        c.setPhoneNumberId("pn");
        c.setContactId(contactId);
        return c;
    }

    private static WaWebMessage message(MessageDirection direction) {
        WaWebMessage m = new WaWebMessage();
        m.setDirection(direction);
        return m;
    }

    @Test
    void aFetchedChatHandsItsContactToTheResolver() {
        // the phone behind a hidden id is never on a stored message, so an imported chat needs the lookup too
        listener.on(new InboxEvents.ChatFetched(chat("c1")));
        verify(resolver).afterMessage("ws", "pn", "c1");
    }

    @Test
    void aFetchedChatWithoutAContactIsLeftAlone() {
        listener.on(new InboxEvents.ChatFetched(chat(null)));
        verify(resolver, never()).afterMessage(any(), any(), any());
    }

    @Test
    void aNewInboundMessageHandsItsContactToTheResolver() {
        listener.on(new InboxEvents.MessageUpserted(chat("c1"), message(MessageDirection.IN), true));
        verify(resolver).afterMessage("ws", "pn", "c1");
    }

    @Test
    void anOutboundOrRepeatedMessageDoesNot() {
        listener.on(new InboxEvents.MessageUpserted(chat("c1"), message(MessageDirection.OUT), true));
        listener.on(new InboxEvents.MessageUpserted(chat("c1"), message(MessageDirection.IN), false));
        verify(resolver, never()).afterMessage(any(), any(), any());
    }
}
