package me.nageshk.omnichannel.crm.deal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

class PositionsTest {

    @Test
    void appendAddsGap() {
        assertThat(Positions.between(null, null)).hasValue(Positions.GAP);
        assertThat(Positions.between(3072.0, null)).hasValue(3072.0 + Positions.GAP);
    }

    @Test
    void insertAtTopHalvesFirst() {
        assertThat(Positions.between(null, 1024.0)).hasValue(512.0);
        assertThat(Positions.between(null, 1.0)).hasValue(0.5);
    }

    @Test
    void midpointBetweenNeighbours() {
        assertThat(Positions.between(1024.0, 2048.0)).hasValue(1536.0);
        assertThat(Positions.between(1.0, 2.0)).hasValue(1.5);
    }

    @Test
    void tooSmallGapAsksForRespace() {
        assertThat(Positions.between(1.0, 1.0 + 1e-7)).isEmpty();
        assertThat(Positions.between(5.0, 5.0)).isEmpty();
        assertThat(Positions.between(null, 1e-7)).isEmpty();
        OptionalDouble justEnough = Positions.between(1.0, 1.0 + 1e-5);
        assertThat(justEnough).isPresent();
        assertThat(justEnough.getAsDouble()).isStrictlyBetween(1.0, 1.0 + 1e-5);
    }

    @Test
    void respacedIsEvenlyGapped() {
        assertThat(Positions.respaced(0)).isEmpty();
        assertThat(Positions.respaced(3)).isEqualTo(List.of(1024.0, 2048.0, 3072.0));
    }
}
