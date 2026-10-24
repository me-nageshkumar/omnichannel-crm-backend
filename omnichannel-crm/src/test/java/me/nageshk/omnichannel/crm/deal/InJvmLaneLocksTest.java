package me.nageshk.omnichannel.crm.deal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class InJvmLaneLocksTest {

    private final InJvmLaneLocks locks = new InJvmLaneLocks();
    private int counter;

    @Test
    void oneLaneRunsOneWorkAtATime() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> done = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                done.add(pool.submit(() -> {
                    for (int i = 0; i < 1000; i++) {
                        locks.withLane("ws", "p", "s", () -> {
                            int read = counter;
                            Thread.onSpinWait();
                            counter = read + 1;
                            return null;
                        });
                    }
                }));
            }
            for (Future<?> f : done) f.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(counter).isEqualTo(8000);
    }

    @Test
    void twoLanesTakenInEitherOrderDoNotDeadlock() throws Exception {
        LaneLocks.Lane a = new LaneLocks.Lane("p", "a");
        LaneLocks.Lane b = new LaneLocks.Lane("p", "b");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> ab = pool.submit(() -> {
                for (int i = 0; i < 2000; i++) locks.withLanes("ws", List.of(a, b), () -> null);
            });
            Future<?> ba = pool.submit(() -> {
                for (int i = 0; i < 2000; i++) locks.withLanes("ws", List.of(b, a), () -> null);
            });
            ab.get(30, TimeUnit.SECONDS);
            ba.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void anotherBrandsLaneOfTheSameIdsDoesNotWait() throws Exception {
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            String inner = locks.withLane("ws", "p", "s", () -> {
                try {
                    return other.submit(() -> locks.withLane("other", "p", "s", () -> "ran")).get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            assertThat(inner).isEqualTo("ran");
        } finally {
            other.shutdownNow();
        }
    }
}
