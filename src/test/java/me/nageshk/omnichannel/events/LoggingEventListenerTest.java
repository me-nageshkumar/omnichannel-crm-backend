package me.nageshk.omnichannel.events;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class LoggingEventListenerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Logger logger = (Logger) LoggerFactory.getLogger(LoggingEventListener.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private Level originalLevel;

    @BeforeEach
    void capture() {
        originalLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        logged.start();
        logger.addAppender(logged);
    }

    @AfterEach
    void release() {
        logger.detachAppender(logged);
        logger.setLevel(originalLevel);
    }

    private List<String> at(Level level) {
        return logged.list.stream().filter(e -> e.getLevel() == level).map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private void publish(String name, String payload) throws Exception {
        new LoggingEventListener().on(new WhatsAppEvent(this, "pn-1", "ws-1", "cust-1", name,
                mapper.readTree(payload), 7L, 1_700_000_000_000L));
    }

    @Test
    void messageBodiesAndPayloadsAreLoggedAtDebugOnly() throws Exception {
        publish("message_create", "{\"id\":{\"_serialized\":\"true_1@c.us_A\",\"remote\":\"1@c.us\"},"
                + "\"from\":\"2@c.us\",\"type\":\"chat\",\"body\":\"my secret pin 1234\"}");
        publish("some_other_event", "{\"text\":\"private note\"}");

        assertThat(at(Level.INFO)).hasSize(2);
        assertThat(at(Level.INFO).get(0)).contains("pn-1").contains("message_create").contains("true_1@c.us_A")
                .contains("1@c.us").contains("chat").contains("seq=7");
        assertThat(at(Level.INFO).get(1)).contains("some_other_event");
        assertThat(at(Level.INFO)).noneMatch(m -> m.contains("secret") || m.contains("private note"));
        assertThat(at(Level.DEBUG)).anyMatch(m -> m.contains("my secret pin 1234"));
        assertThat(at(Level.DEBUG)).anyMatch(m -> m.contains("private note"));
    }

    @Test
    void widAcceptsStringsAndWidObjects() throws Exception {
        assertThat(LoggingEventListener.wid(mapper.readTree("\"1@c.us\""))).isEqualTo("1@c.us");
        assertThat(LoggingEventListener.wid(mapper.readTree("{\"server\":\"lid\",\"user\":\"115\",\"_serialized\":\"115@lid\"}")))
                .isEqualTo("115@lid");
        assertThat(LoggingEventListener.wid(mapper.readTree("{\"server\":\"c.us\",\"user\":\"9\"}"))).isEqualTo("9@c.us");
        assertThat(LoggingEventListener.wid(mapper.missingNode())).isEqualTo("");
    }

    @Test
    void messageIdUsesSerializedOrRebuildsFromParts() throws Exception {
        JsonNode withSerialized = mapper.readTree("{\"fromMe\":true,\"remote\":\"1@c.us\",\"id\":\"A\",\"_serialized\":\"true_1@c.us_A\"}");
        assertThat(LoggingEventListener.messageId(withSerialized)).isEqualTo("true_1@c.us_A");

        JsonNode mangled = mapper.readTree("{\"fromMe\":true,\"remote\":\"103@lid\",\"id\":\"3EB0\",\"$1\":\"true_103@lid_3EB0\"}");
        assertThat(LoggingEventListener.messageId(mangled)).isEqualTo("true_103@lid_3EB0");

        assertThat(LoggingEventListener.messageId(mapper.readTree("{}"))).isEqualTo("");
    }
}
