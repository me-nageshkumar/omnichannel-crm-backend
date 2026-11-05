package me.nageshk.omnichannel.service.events;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/** The log and the debugging store see every event first, even one whose ingestion then fails. */
class EventListenerOrderTest {

    @Test
    void theLogAndTheRecentStoreRunBeforeTheIngestingListeners() throws NoSuchMethodException {
        assertThat(LoggingEventListener.class.getMethod("on", WhatsAppEvent.class).getAnnotation(Order.class).value())
                .isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        assertThat(RecentEventsStore.class.getMethod("on", WhatsAppEvent.class).getAnnotation(Order.class).value())
                .isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }
}
