package me.nageshk.omnichannel.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MessageStatusTest {

    @Test
    void ackMapsToStatus() {
        assertThat(MessageStatus.ofAck(-1)).isEqualTo(MessageStatus.FAILED);
        assertThat(MessageStatus.ofAck(0)).isEqualTo(MessageStatus.PENDING);
        assertThat(MessageStatus.ofAck(1)).isEqualTo(MessageStatus.SENT);
        assertThat(MessageStatus.ofAck(2)).isEqualTo(MessageStatus.DELIVERED);
        assertThat(MessageStatus.ofAck(3)).isEqualTo(MessageStatus.READ);
        assertThat(MessageStatus.ofAck(4)).isEqualTo(MessageStatus.PLAYED);
        assertThat(MessageStatus.ofAck(9)).isEqualTo(MessageStatus.PENDING);
    }

    @Test
    void statusNeverGoesDown() {
        assertThat(MessageStatus.READ.isAtLeast(MessageStatus.SENT)).isTrue();
        assertThat(MessageStatus.SENT.isAtLeast(MessageStatus.READ)).isFalse();
        assertThat(MessageStatus.FAILED.isAtLeast(MessageStatus.READ)).isFalse();
    }

    @Test
    void failedSitsOutsideTheRankOrder() {
        assertThat(MessageStatus.FAILED.isAtLeast(MessageStatus.FAILED)).isTrue();
        assertThat(MessageStatus.FAILED.isAtLeast(MessageStatus.PENDING)).isTrue();
        assertThat(MessageStatus.FAILED.isAtLeast(MessageStatus.SENT)).isFalse();
        for (MessageStatus s : MessageStatus.values()) {
            if (s != MessageStatus.FAILED) assertThat(s.isAtLeast(MessageStatus.FAILED)).isFalse();
        }
        assertThat(MessageStatus.PLAYED.isAtLeast(MessageStatus.PLAYED)).isTrue();
        assertThat(MessageStatus.PENDING.isAtLeast(MessageStatus.PENDING)).isTrue();
    }
}
