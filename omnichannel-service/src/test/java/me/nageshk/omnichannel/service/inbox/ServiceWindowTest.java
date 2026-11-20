package me.nageshk.omnichannel.service.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import org.junit.jupiter.api.Test;

class ServiceWindowTest {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

    private static WaWebChat chat(Instant lastInbound) {
        WaWebChat c = new WaWebChat();
        c.setLastInboundAt(lastInbound);
        return c;
    }

    @Test
    void openJustBeforeTwentyFourHours() {
        Instant in = NOW.minus(Duration.ofHours(23)).minus(Duration.ofMinutes(59));
        ServiceWindow.ChatWindow w = ServiceWindow.of(chat(in), NOW);
        assertThat(w.open()).isTrue();
        assertThat(w.closesAt()).isEqualTo(in.plus(Duration.ofHours(24)));
    }

    @Test
    void closedAtExactlyTwentyFourHours() {
        assertThat(ServiceWindow.of(chat(NOW.minus(Duration.ofHours(24))), NOW).open()).isFalse();
    }

    @Test
    void closedWithoutAnyCustomerMessage() {
        ServiceWindow.ChatWindow w = ServiceWindow.of(chat(null), NOW);
        assertThat(w.open()).isFalse();
        assertThat(w.closesAt()).isNull();
    }
}
