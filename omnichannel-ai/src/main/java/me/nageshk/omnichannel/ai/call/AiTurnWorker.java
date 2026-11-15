package me.nageshk.omnichannel.ai.call;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import me.nageshk.omnichannel.ai.api.AiTurnListener;
import me.nageshk.omnichannel.ai.api.TurnResult;
import me.nageshk.omnichannel.ai.config.AiProperties;
import me.nageshk.omnichannel.ai.turn.AiTurnRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

/**
 * Takes turns off the queue and runs them on the AI pool, never on a chatbot thread (spec §5.5). Each finished turn
 * goes to every {@link AiTurnListener}; one that no listener took is offered again after {@code redeliverAfter}.
 */
@Service
public class AiTurnWorker {

    private static final Logger log = LoggerFactory.getLogger(AiTurnWorker.class);
    /** Claims stop while this many runs wait for a thread, so a claimed turn is never refused by the pool. */
    private static final int MAX_WAITING = 400;

    private final AiTurnQueue queue;
    private final AiTurnRunner runner;
    private final ObjectProvider<AiTurnListener> listeners;
    private final ThreadPoolTaskExecutor pool;
    private final AiProperties props;
    private final Clock clock;

    public AiTurnWorker(AiTurnQueue queue, AiTurnRunner runner, ObjectProvider<AiTurnListener> listeners,
            @Qualifier("aiExecutor") ThreadPoolTaskExecutor pool, AiProperties props, Clock clock) {
        this.queue = queue;
        this.runner = runner;
        this.listeners = listeners;
        this.pool = pool;
        this.props = props;
        this.clock = clock;
    }

    /**
     * Re-offers undelivered results, then claims until the queue is empty, running each claimed turn on the pool.
     * The future completes when those runs and deliveries are done (tests wait on it; the schedule does not).
     */
    public CompletableFuture<Void> tick() {
        List<CompletableFuture<Void>> work = new ArrayList<>();
        try {
            Instant now = clock.instant();
            for (AiCall c : queue.undelivered(now.minus(props.redeliverAfter()))) {
                work.add(CompletableFuture.runAsync(() -> deliver(c), pool));
            }
            while (pool.getQueueSize() < MAX_WAITING) {
                Optional<AiCall> claimed = queue.claim(clock.instant());
                if (claimed.isEmpty()) break;
                AiCall c = claimed.get();
                work.add(CompletableFuture.runAsync(() -> process(c), pool));
            }
        } catch (RejectedExecutionException e) {
            log.warn("the AI pool refused a turn; its lease will run out and it is claimed again");
        } catch (RuntimeException e) {
            log.warn("AI tick failed: {}", e.toString());
        }
        return CompletableFuture.allOf(work.toArray(CompletableFuture[]::new));
    }

    private void process(AiCall c) {
        if (c.getAttempts() > props.attempts()) {
            c.fail("INTERRUPTED", "The turn was cut off " + (c.getAttempts() - 1) + " times (a restart or a crash)");
            c.setFinishedAt(clock.instant());
        } else {
            try {
                runner.run(c);
            } catch (RuntimeException e) {
                log.warn("AI call {} failed outside the turn", c.getId(), e);
                c.fail("INTERNAL", "The turn failed (" + e.getClass().getSimpleName() + ")");
                c.setFinishedAt(clock.instant());
            }
        }
        if (!queue.finish(c)) {
            log.info("AI call {} was claimed again while it ran; this result is dropped", c.getId());
            return;
        }
        deliver(c);
    }

    private void deliver(AiCall c) {
        TurnResult r = c.toResult();
        boolean all = true;
        for (AiTurnListener l : listeners.orderedStream().toList()) {
            try {
                l.turnFinished(r);
            } catch (RuntimeException e) {
                all = false;
                log.warn("AI turn listener {} failed on call {}: {}", l.getClass().getSimpleName(), c.getId(),
                        e.toString());
            }
        }
        if (all) queue.delivered(c.getId());
    }
}
