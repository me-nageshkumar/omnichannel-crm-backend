package me.nageshk.omnichannel.ai.call;

import java.time.Instant;
import java.util.Optional;

/** The AI turn queue; MongoDB today ({@link MongoAiTurnQueue}), replaceable by SQS or Kafka behind this interface. */
public interface AiTurnQueue {

    /** Redeliveries of one result before it is left undelivered (about 5 minutes at the default 10 s). */
    int MAX_DELIVERY_ATTEMPTS = 30;

    /** Inserts a PENDING call; returns its id. */
    String enqueue(AiCall c);

    /**
     * Claims the oldest PENDING call, or a RUNNING one whose lease ran out: RUNNING, a new lease, attempts + 1. The
     * caller checks the attempts against the limit.
     */
    Optional<AiCall> claim(Instant now);

    /**
     * Writes a claimed call's result (not its status) while the claim is still held, before the conversation moves
     * on; false when the claim was lost, and then nothing may be written anywhere.
     */
    boolean stage(AiCall c);

    /** Stores the outcome of a claimed call; false when the claim was lost (its lease ran out and it was claimed again). */
    boolean finish(AiCall c);

    /**
     * Claims one DONE or FAILED call no listener has taken yet whose next delivery is due: the next one moves
     * {@code redeliverAfter} on and the attempts go up, so it is offered at most that often, up to
     * {@link #MAX_DELIVERY_ATTEMPTS} times.
     */
    Optional<AiCall> claimRedelivery(Instant now);

    void delivered(String id);
}
