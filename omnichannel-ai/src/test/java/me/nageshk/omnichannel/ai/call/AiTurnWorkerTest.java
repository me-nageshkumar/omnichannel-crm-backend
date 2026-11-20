package me.nageshk.omnichannel.ai.call;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import me.nageshk.omnichannel.ai.api.AiTurnListener;
import me.nageshk.omnichannel.ai.api.TurnRequest;
import me.nageshk.omnichannel.ai.api.TurnResult;
import me.nageshk.omnichannel.ai.config.AiProperties;
import me.nageshk.omnichannel.ai.turn.AiTurnRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** The worker against an in-memory queue and a one-thread pool. */
class AiTurnWorkerTest {

    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");

    /** PENDING rows in order; finish answers {@link #claimHeld}. */
    static final class Queue implements AiTurnQueue {
        final List<AiCall> pending = new ArrayList<>();
        final List<String> delivered = new CopyOnWriteArrayList<>();
        volatile boolean claimHeld = true;

        @Override
        public String enqueue(AiCall c) {
            pending.add(c);
            return c.getId();
        }

        @Override
        public synchronized Optional<AiCall> claim(Instant now) {
            if (pending.isEmpty()) return Optional.empty();
            AiCall c = pending.remove(0);
            c.setStatus(AiCallStatus.RUNNING);
            c.setAttempts(c.getAttempts() + 1);
            return Optional.of(c);
        }

        @Override
        public boolean stage(AiCall c) {
            return claimHeld;
        }

        @Override
        public boolean finish(AiCall c) {
            return claimHeld;
        }

        @Override
        public Optional<AiCall> claimRedelivery(Instant now) {
            return Optional.empty();
        }

        @Override
        public void delivered(String id) {
            delivered.add(id);
        }
    }

    private final Queue queue = new Queue();
    private final AiTurnRunner runner = mock(AiTurnRunner.class);
    private final List<TurnResult> heard = new CopyOnWriteArrayList<>();
    private final ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();

    private AiTurnWorker worker() {
        pool.setCorePoolSize(1);
        pool.setMaxPoolSize(1);
        pool.setQueueCapacity(500);
        pool.initialize();
        StaticListableBeanFactory beans = new StaticListableBeanFactory(
                Map.of("listener", (AiTurnListener) heard::add));
        AiProperties props = new AiProperties(false, Duration.ofSeconds(2), 1, Duration.ofSeconds(60),
                Duration.ofSeconds(120), 2, 8, Duration.ofSeconds(10), new AiProperties.OpenAi("x"),
                new AiProperties.Mcp(Duration.ofSeconds(20), 65536, Duration.ofMinutes(5)),
                AiProperties.Knowledge.defaults());
        return new AiTurnWorker(queue, runner, beans.getBeanProvider(AiTurnListener.class), pool, props,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void stopPool() {
        pool.shutdown();
    }

    private static AiCall row(String id) {
        AiCall c = AiCall.pending(new TurnRequest("ws", "run", "bot", "Ask", "p", null, null, List.of("Hi"),
                List.of(), List.of(), false, false, null, null, null), "conv", "scripted", "m", NOW);
        c.setId(id);
        return c;
    }

    @Test
    void claimsOnlyWhileAThreadIsFree() throws Exception {
        AiTurnWorker worker = worker();
        for (String id : List.of("a", "b", "c")) queue.enqueue(row(id));
        CountDownLatch release = new CountDownLatch(1);
        when(runner.run(any())).thenAnswer(inv -> {
            release.await(5, TimeUnit.SECONDS);
            AiCall c = inv.getArgument(0);
            c.setStatus(AiCallStatus.DONE);
            return c.toResult();
        });

        var first = worker.tick();

        assertThat(queue.pending).as("the other two stay PENDING, their lease not started")
                .extracting(AiCall::getId).containsExactly("b", "c");
        release.countDown();
        first.get(5, TimeUnit.SECONDS);
        for (int i = 0; i < 500 && pool.getActiveCount() > 0; i++) Thread.sleep(10);
        worker.tick().get(5, TimeUnit.SECONDS);
        assertThat(queue.pending).extracting(AiCall::getId).containsExactly("c");
    }

    @Test
    void lostClaimIsNotDelivered() throws Exception {
        AiTurnWorker worker = worker();
        queue.enqueue(row("a"));
        queue.claimHeld = false;
        when(runner.run(any())).thenAnswer(inv -> {
            AiCall c = inv.getArgument(0);
            c.setStatus(AiCallStatus.DONE);
            return c.toResult();
        });

        worker.tick().get(5, TimeUnit.SECONDS);

        assertThat(heard).isEmpty();
        assertThat(queue.delivered).isEmpty();
    }
}
