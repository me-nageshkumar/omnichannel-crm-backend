package me.nageshk.omnichannel.service.inbox.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.result.UpdateResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import me.nageshk.omnichannel.common.config.OmnichannelProperties;
import me.nageshk.omnichannel.db.inbox.ChatStatus;
import me.nageshk.omnichannel.db.inbox.MessageDirection;
import me.nageshk.omnichannel.db.inbox.RoutedReason;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatRepository;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImport;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImportChat;
import me.nageshk.omnichannel.service.inbox.ChatRoutingService;
import me.nageshk.omnichannel.service.inbox.InboxEvents;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

class FetchedChatOpenerTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");

    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final WaWebChatRepository chats = mock(WaWebChatRepository.class);
    private final ChatRoutingService routing = mock(ChatRoutingService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final FetchedChatOpener opener = new FetchedChatOpener(mongo, chats, routing, events,
            Clock.fixed(NOW, ZoneOffset.UTC), props());

    private static OmnichannelProperties props() {
        var history = new OmnichannelProperties.History(true, Duration.ofSeconds(5), 100, 20, Duration.ZERO,
                Duration.ZERO, 5000, 3, 25, Duration.ofSeconds(60), 3, Duration.ofDays(7));
        return new OmnichannelProperties(null, null, null, null, null, history);
    }

    private static WaWebChat chat(ChatStatus status, String assignedUserId) {
        WaWebChat c = new WaWebChat();
        c.setId("c1");
        c.setStatus(status);
        c.setAssignedUserId(assignedUserId);
        c.setLastInboundAt(NOW.minus(Duration.ofHours(1)));
        c.setLastMessage(new WaWebChat.LastMessage(MessageDirection.IN, "chat", "hello", null));
        return c;
    }

    @Test
    void routingThatCollidesWithALiveMessageIsTriedAgainOnTheChatAsStored() {
        WaWebHistoryImport imp = new WaWebHistoryImport();
        WaWebHistoryImportChat item = new WaWebHistoryImportChat();
        item.setCreatedChatId("c1");
        item.setBusiness(false);
        item.setPhoneUnread(1);
        WaWebChat assigned = chat(ChatStatus.OPEN, "agent-1");
        when(chats.findById("c1")).thenReturn(Optional.of(chat(ChatStatus.CLOSED, null)),
                Optional.of(chat(ChatStatus.OPEN, null)), Optional.of(chat(ChatStatus.OPEN, null)),
                Optional.of(assigned));
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(WaWebChat.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));
        when(routing.route(any(WaWebChat.class), eq(RoutedReason.FETCHED)))
                .thenThrow(new OptimisticLockingFailureException("a live message saved the chat"))
                .thenReturn(assigned);

        opener.openIfWaiting(imp, item);

        verify(routing, times(2)).route(any(WaWebChat.class), eq(RoutedReason.FETCHED));
        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(published.capture());
        assertThat(published.getValue()).isInstanceOfSatisfying(InboxEvents.ChatChanged.class,
                e -> assertThat(e.chat().getAssignedUserId()).isEqualTo("agent-1"));
    }
}
