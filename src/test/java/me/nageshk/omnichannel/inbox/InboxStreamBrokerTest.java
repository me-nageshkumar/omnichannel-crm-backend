package me.nageshk.omnichannel.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import me.nageshk.omnichannel.api.NotFoundException;
import me.nageshk.omnichannel.inbox.InboxScope.Level;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

class InboxStreamBrokerTest {

    private static final String PN = "pn1";

    /** Captures what would have been written to the wire, one string per event. */
    static class RecordingEmitter extends SseEmitter {
        final List<String> events = new CopyOnWriteArrayList<>();
        boolean failOnSend;
        boolean completed;

        RecordingEmitter() {
            super(0L);
        }

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            if (failOnSend) {
                throw new IOException("broken pipe");
            }
            StringBuilder sb = new StringBuilder();
            builder.build().forEach(part -> sb.append(part.getData()));
            events.add(sb.toString());
        }

        @Override
        public synchronized void complete() {
            completed = true;
        }
    }

    private final List<RecordingEmitter> created = new ArrayList<>();
    private final InboxStreamBroker broker = new InboxStreamBroker(JsonMapper.builder().build(), timeout -> {
        RecordingEmitter e = new RecordingEmitter();
        created.add(e);
        return e;
    });

    private static InboxScope mine(String userId) {
        return new InboxScope(Level.MINE, userId, Set.of());
    }

    private static WaWebChat chatAssignedTo(String userId) {
        WaWebChat c = new WaWebChat();
        c.setId("c1");
        c.setPhoneNumberId(PN);
        c.setAssignedUserId(userId);
        return c;
    }

    private static ChatView chatView() {
        return new ChatView("c1", PN, "9199@c.us", null, ChatStatus.OPEN, null, null, 0, null, null, null);
    }

    private static MessageView messageView() {
        return new MessageView("m1", "c1", "wa1", MessageDirection.IN, MessageSource.CONTACT, "chat", "hi",
                null, null, null, MessageStatus.SENT, null, null, null);
    }

    private static ActionView actionView() {
        return new ActionView("a1", ActionType.CLOSED, null, null, null, null);
    }

    @Test
    void chatEventCarriesVisibleFalseForReassignedChat() {
        broker.subscribe(PN, () -> mine("u1"));

        broker.push(PN, chatAssignedTo("u2"), chatView());

        assertThat(created).hasSize(1);
        assertThat(created.get(0).events).hasSize(1);
        String event = created.get(0).events.get(0);
        assertThat(event).startsWith("event:chat\ndata:");
        assertThat(event).contains("\"visible\":false").contains("\"chat\":{").contains("\"id\":\"c1\"");
    }

    @Test
    void chatEventCarriesVisibleTrueInsideScope() {
        broker.subscribe(PN, () -> mine("u1"));

        broker.push(PN, chatAssignedTo("u1"), chatView());

        assertThat(created.get(0).events.get(0)).contains("\"visible\":true");
    }

    @Test
    void messageIsNotPushedOutsideScope() {
        broker.subscribe(PN, () -> mine("u1"));

        broker.pushMessage(PN, chatAssignedTo("u2"), messageView());

        assertThat(created.get(0).events).isEmpty();
    }

    @Test
    void messagePushedInsideScope() {
        broker.subscribe(PN, () -> mine("u1"));

        broker.pushMessage(PN, chatAssignedTo("u1"), messageView());

        assertThat(created.get(0).events).hasSize(1);
        String event = created.get(0).events.get(0);
        assertThat(event).startsWith("event:message\ndata:");
        assertThat(event).contains("\"chatId\":\"c1\"").contains("\"message\":{").contains("\"waMessageId\":\"wa1\"");
    }

    @Test
    void actionPushedOnlyInsideScope() {
        broker.subscribe(PN, () -> mine("u1"));
        broker.subscribe(PN, () -> mine("u2"));

        broker.pushAction(PN, chatAssignedTo("u1"), actionView());

        assertThat(created.get(0).events).hasSize(1);
        assertThat(created.get(0).events.get(0)).startsWith("event:action\ndata:")
                .contains("\"chatId\":\"c1\"").contains("\"action\":{");
        assertThat(created.get(1).events).isEmpty();
    }

    @Test
    void pushOnlyReachesSubscribersOfThatNumber() {
        broker.subscribe(PN, () -> mine("u1"));
        broker.subscribe("pn2", () -> mine("u1"));

        broker.push(PN, chatAssignedTo("u1"), chatView());

        assertThat(created.get(0).events).hasSize(1);
        assertThat(created.get(1).events).isEmpty();
    }

    @Test
    void failedEmitterIsDropped() {
        broker.subscribe(PN, () -> mine("u1"));
        broker.subscribe(PN, () -> mine("u1"));
        created.get(0).failOnSend = true;
        assertThat(broker.subscribers(PN)).isEqualTo(2);

        broker.push(PN, chatAssignedTo("u1"), chatView());

        assertThat(broker.subscribers(PN)).isEqualTo(1);
        assertThat(created.get(1).events).hasSize(1);
    }

    @Test
    void subscriberWhoLostAccessIsCompletedAndDropped() {
        broker.subscribe(PN, () -> {
            throw new NotFoundException("gone");
        });

        broker.push(PN, chatAssignedTo("u1"), chatView());

        assertThat(created.get(0).completed).isTrue();
        assertThat(created.get(0).events).isEmpty();
        assertThat(broker.subscribers(PN)).isZero();
    }

    @Test
    void pingReachesAllSubscribers() {
        broker.subscribe(PN, () -> mine("u1"));
        broker.subscribe("pn2", () -> mine("u2"));

        broker.ping();

        assertThat(created.get(0).events).hasSize(1);
        assertThat(created.get(0).events.get(0)).contains("ping");
        assertThat(created.get(1).events).hasSize(1);
    }
}
