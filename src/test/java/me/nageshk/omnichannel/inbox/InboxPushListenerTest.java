package me.nageshk.omnichannel.inbox;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import me.nageshk.omnichannel.inbox.InboxEvents.ActionRecorded;
import me.nageshk.omnichannel.inbox.InboxEvents.ChatChanged;
import me.nageshk.omnichannel.inbox.InboxEvents.MessageUpserted;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InboxPushListenerTest {

    private static final String PN = "pn1";

    private final InboxStreamBroker broker = mock(InboxStreamBroker.class);
    private final InboxViews views = mock(InboxViews.class);
    private final InboxPushListener listener = new InboxPushListener(broker, views);

    private final WaWebChat chat = new WaWebChat();
    private final WaWebMessage message = new WaWebMessage();
    private final WaWebChatAction action = new WaWebChatAction();
    private final ChatView chatView = mock(ChatView.class);
    private final MessageView messageView = mock(MessageView.class);
    private final ActionView actionView = mock(ActionView.class);

    @BeforeEach
    void setUp() {
        chat.setId("c1");
        chat.setPhoneNumberId(PN);
        message.setId("m1");
        action.setId("a1");
        when(broker.subscribers(PN)).thenReturn(1);
        when(views.chat(chat)).thenReturn(chatView);
        when(views.message(message)).thenReturn(messageView);
        when(views.action(action)).thenReturn(actionView);
    }

    @Test
    void messageUpsertedPushesOnlyTheMessageBecauseChatChangedFollowsIt() {
        listener.on(new MessageUpserted(chat, message, true));

        verify(broker).pushMessage(PN, chat, messageView);
        verify(broker, never()).push(any(), any(), any());
    }

    @Test
    void chatChangedPushesChat() {
        listener.on(new ChatChanged(chat));

        verify(broker).push(PN, chat, chatView);
    }

    @Test
    void actionRecordedPushesAction() {
        listener.on(new ActionRecorded(chat, action));

        verify(broker).pushAction(PN, chat, actionView);
    }

    @Test
    void noSubscribersBuildsNoViews() {
        when(broker.subscribers(PN)).thenReturn(0);

        listener.on(new MessageUpserted(chat, message, true));
        listener.on(new ChatChanged(chat));
        listener.on(new ActionRecorded(chat, action));

        verifyNoInteractions(views);
    }

    @Test
    void brokerFailureIsSwallowed() {
        org.mockito.Mockito.doThrow(new IllegalStateException("boom")).when(broker)
                .pushMessage(any(), any(), any());
        org.mockito.Mockito.doThrow(new IllegalStateException("boom")).when(broker).push(any(), any(), any());
        org.mockito.Mockito.doThrow(new IllegalStateException("boom")).when(broker)
                .pushAction(any(), any(), any());

        assertThatCode(() -> {
            listener.on(new MessageUpserted(chat, message, true));
            listener.on(new ChatChanged(chat));
            listener.on(new ActionRecorded(chat, action));
        }).doesNotThrowAnyException();
    }
}
