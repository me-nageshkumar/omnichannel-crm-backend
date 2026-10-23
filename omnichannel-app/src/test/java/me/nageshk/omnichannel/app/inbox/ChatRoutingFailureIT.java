package me.nageshk.omnichannel.app.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatAction;
import me.nageshk.omnichannel.db.inbox.WaWebChatRepository;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.db.inbox.WaWebMessageRepository;
import me.nageshk.omnichannel.service.events.WhatsAppEvent;
import me.nageshk.omnichannel.service.inbox.ChatRoutingService;
import me.nageshk.omnichannel.service.inbox.InboxEvents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest
class ChatRoutingFailureIT {

    static class Recorder {
        final List<Object> events = new CopyOnWriteArrayList<>();

        @EventListener
        void on(InboxEvents.MessageUpserted e) {
            events.add(e);
        }

        @EventListener
        void on(InboxEvents.ChatChanged e) {
            events.add(e);
        }
    }

    @TestConfiguration
    static class RecorderConfig {
        @Bean
        Recorder routingFailureRecorder() {
            return new Recorder();
        }
    }

    @MockitoSpyBean ChatRoutingService routing;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired WaWebChatRepository chats;
    @Autowired WaWebMessageRepository messages;
    @Autowired MongoTemplate mongo;
    @Autowired ObjectMapper mapper;
    @Autowired Recorder recorder;

    @BeforeEach
    void setUp() {
        clean();
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        for (Class<?> c : List.of(WaWebChat.class, WaWebMessage.class, WaWebChatAction.class))
            mongo.remove(new Query(), c);
        mongo.remove(new Query(), "contacts");
        recorder.events.clear();
    }

    @Test
    void aRoutingFailureDoesNotStopTheMessageReachingTheStreams() {
        doThrow(new IllegalStateException("routing down")).when(routing).route(any());
        ObjectNode p = mapper.createObjectNode();
        ObjectNode id = p.putObject("id");
        id.put("fromMe", false);
        id.put("remote", "919911111111@c.us");
        id.put("id", "RF1");
        p.put("fromMe", false);
        p.put("timestamp", 1_700_000_000L);
        p.put("body", "hi");

        publisher.publishEvent(
                new WhatsAppEvent(this, "pn-fail", "ws-fail", "cust-fail", "message_create", p, null, null));

        assertThat(chats.count()).isEqualTo(1);
        assertThat(messages.count()).isEqualTo(1);
        assertThat(recorder.events).hasAtLeastOneElementOfType(InboxEvents.MessageUpserted.class);
        assertThat(recorder.events).hasAtLeastOneElementOfType(InboxEvents.ChatChanged.class);
        assertThat(chats.findAll().getFirst().getAssignedUserId()).isNull();
    }
}
