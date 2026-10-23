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
    private static final String WS = "ws1";

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

    /** Own chats only; the viewer's team owns {@link #PN}, so that number's queue is theirs and "pn2" is a guest. */
    private static InboxScope mine(String userId) {
        return new InboxScope(Level.MINE, userId, Set.of(), Set.of(PN));
    }

    /** A viewer who sees only their own chats, looking at the given numbers (every visible one when none given). */
    private static InboxStreamBroker.Viewer viewer(String userId, String... numbers) {
        return new InboxStreamBroker.Viewer(mine(userId), Set.of(numbers));
    }

    private static WaWebChat chatAssignedTo(String userId) {
        return chatAssignedTo(userId, PN);
    }

    private static WaWebChat chatAssignedTo(String userId, String phoneNumberId) {
        WaWebChat c = new WaWebChat();
        c.setId("c1");
        c.setWorkspaceId(WS);
        c.setPhoneNumberId(phoneNumberId);
        c.setAssignedUserId(userId);
        return c;
    }

    private static ChatView chatView() {
        return new ChatView("c1", PN, "9199@c.us", null, ChatStatus.OPEN, null, null, 0, null, null, null, null);
    }

    private static MessageView messageView() {
        return new MessageView("m1", "c1", "wa1", MessageDirection.IN, MessageSource.CONTACT, "chat", "hi",
                null, null, null, MessageStatus.SENT, null, null, null);
    }

    private static ActionView actionView() {
        return new ActionView("a1", ActionType.CLOSED, null, null, null, null, null, null);
    }

    @Test
    void chatEventCarriesVisibleFalseForReassignedChat() {
        broker.subscribe(WS, () -> viewer("u1"));

        broker.push(WS, chatAssignedTo("u2"), chatView());

        assertThat(created).hasSize(1);
        assertThat(created.get(0).events).hasSize(1);
        String event = created.get(0).events.get(0);
        assertThat(event).startsWith("event:chat\ndata:");
        assertThat(event).contains("\"visible\":false").contains("\"chat\":{").contains("\"id\":\"c1\"")
                .contains("\"phoneNumberId\":\"pn1\"")
                .doesNotContain("contact").doesNotContain("lastMessage").doesNotContain("assignedUser");
    }

    @Test
    void chatEventCarriesVisibleTrueInsideScope() {
        broker.subscribe(WS, () -> viewer("u1"));

        broker.push(WS, chatAssignedTo("u1"), chatView());

        assertThat(created.get(0).events.get(0)).contains("\"visible\":true");
    }

    @Test
    void messageIsNotPushedOutsideScope() {
        broker.subscribe(WS, () -> viewer("u1"));

        broker.pushMessage(WS, chatAssignedTo("u2"), messageView());

        assertThat(created.get(0).events).isEmpty();
    }

    @Test
    void messagePushedInsideScope() {
        broker.subscribe(WS, () -> viewer("u1"));

        broker.pushMessage(WS, chatAssignedTo("u1"), messageView());

        assertThat(created.get(0).events).hasSize(1);
        String event = created.get(0).events.get(0);
        assertThat(event).startsWith("event:message\ndata:");
        assertThat(event).contains("\"chatId\":\"c1\"").contains("\"message\":{").contains("\"waMessageId\":\"wa1\"");
    }

    @Test
    void actionPushedOnlyInsideScope() {
        broker.subscribe(WS, () -> viewer("u1"));
        broker.subscribe(WS, () -> viewer("u2"));

        broker.pushAction(WS, chatAssignedTo("u1"), actionView());

        assertThat(created.get(0).events).hasSize(1);
        assertThat(created.get(0).events.get(0)).startsWith("event:action\ndata:")
                .contains("\"chatId\":\"c1\"").contains("\"action\":{");
        assertThat(created.get(1).events).isEmpty();
    }

    @Test
    void pushOnlyReachesSubscribersOfThatWorkspace() {
        broker.subscribe(WS, () -> viewer("u1"));
        broker.subscribe("ws2", () -> viewer("u1"));

        broker.push(WS, chatAssignedTo("u1"), chatView());

        assertThat(created.get(0).events).hasSize(1);
        assertThat(created.get(1).events).isEmpty();
    }

    @Test
    void deliversOnlyForSelectedNumbers() {
        broker.subscribe(WS, () -> viewer("u1", PN));
        broker.subscribe(WS, () -> viewer("u1", "pn2"));
        broker.push(WS, chatAssignedTo("u1", PN), chatView());
        broker.pushMessage(WS, chatAssignedTo("u1", PN), messageView());
        assertThat(created.get(0).events).hasSize(2);
        assertThat(created.get(1).events).isEmpty();
        broker.push(WS, chatAssignedTo("u1", "pn2"), chatView());
        assertThat(created.get(1).events).hasSize(1);
    }

    @Test
    void chatLeavingScopeIsAnnouncedInvisibleOnlyWithinSelectedNumbers() {
        broker.subscribe(WS, () -> viewer("u1", PN));
        broker.push(WS, chatAssignedTo("u2", PN), chatView());
        broker.push(WS, chatAssignedTo("u2", "pn2"), chatView());
        assertThat(created.get(0).events).hasSize(1);
        assertThat(created.get(0).events.get(0)).contains("\"visible\":false");
    }

    @Test
    void chatLeavingAGuestNumberIsAnnouncedInvisibleOnce() {
        broker.subscribe(WS, () -> viewer("u1"));
        broker.push(WS, chatAssignedTo("u1", "pn2"), chatView()); // mine, on a number my team does not own
        broker.push(WS, chatAssignedTo("u2", "pn2"), chatView()); // handed to someone else: I must hear it left
        broker.push(WS, chatAssignedTo("u2", "pn2"), chatView()); // and nothing more about it afterwards
        assertThat(created.get(0).events).hasSize(2);
        assertThat(created.get(0).events.get(0)).contains("\"visible\":true");
        assertThat(created.get(0).events.get(1)).contains("\"visible\":false");
    }

    @Test
    void unseenChatsOnGuestNumbersAreNeverAnnounced() {
        broker.subscribe(WS, () -> viewer("u1"));
        broker.push(WS, chatAssignedTo("u2", "pn2"), chatView());
        broker.pushMessage(WS, chatAssignedTo("u2", "pn2"), messageView());
        assertThat(created.get(0).events).isEmpty();
    }

    @Test
    void failedEmitterIsDropped() {
        broker.subscribe(WS, () -> viewer("u1"));
        broker.subscribe(WS, () -> viewer("u1"));
        created.get(0).failOnSend = true;
        assertThat(broker.subscribers(WS)).isEqualTo(2);

        broker.push(WS, chatAssignedTo("u1"), chatView());

        assertThat(broker.subscribers(WS)).isEqualTo(1);
        assertThat(created.get(1).events).hasSize(1);
    }

    @Test
    void subscriberWhoLostAccessIsCompletedAndDropped() {
        broker.subscribe(WS, () -> {
            throw new NotFoundException("gone");
        });

        broker.push(WS, chatAssignedTo("u1"), chatView());

        assertThat(created.get(0).completed).isTrue();
        assertThat(created.get(0).events).isEmpty();
        assertThat(broker.subscribers(WS)).isZero();
    }

    @Test
    void pingDropsASubscriberWhoLostAccessWithoutSendingToIt() {
        broker.subscribe(WS, () -> {
            throw new NotFoundException("gone");
        });
        broker.subscribe(WS, () -> viewer("u1"));

        broker.ping();

        assertThat(created.get(0).completed).isTrue();
        assertThat(created.get(0).events).isEmpty();
        assertThat(created.get(1).events).hasSize(1);
        assertThat(broker.subscribers(WS)).isEqualTo(1);
    }

    @Test
    void subscribeRacingWithLastRemovalNeverOrphansTheNewSubscriber() throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 500; i++) {
                String pn = "race" + i;
                SseEmitter first = broker.subscribe(pn, () -> viewer("u1"));
                java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);
                var removal = pool.submit(() -> {
                    barrier.await();
                    // a failed send drops (and removes) the only subscriber
                    ((RecordingEmitter) first).failOnSend = true;
                    broker.ping();
                    return null;
                });
                var subscribe = pool.submit(() -> {
                    barrier.await();
                    return broker.subscribe(pn, () -> viewer("u1"));
                });
                removal.get();
                RecordingEmitter second = (RecordingEmitter) subscribe.get();

                broker.ping();

                assertThat(second.events).as("subscriber %d must be reachable", i).isNotEmpty();
                assertThat(broker.subscribers(pn)).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void pingReachesAllSubscribers() {
        broker.subscribe(WS, () -> viewer("u1"));
        broker.subscribe("ws2", () -> viewer("u2"));

        broker.ping();

        assertThat(created.get(0).events).hasSize(1);
        assertThat(created.get(0).events.get(0)).contains("ping");
        assertThat(created.get(1).events).hasSize(1);
    }
}
