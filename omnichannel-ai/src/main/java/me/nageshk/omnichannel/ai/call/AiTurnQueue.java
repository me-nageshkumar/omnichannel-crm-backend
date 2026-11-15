package me.nageshk.omnichannel.ai.call;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** The AI turn queue; MongoDB today ({@link MongoAiTurnQueue}), replaceable by SQS or Kafka behind this interface. */
public interface AiTurnQueue {

    /** Inserts a PENDING call; returns its id. */
    String enqueue(AiCall c);

    /**
     * Claims the oldest PENDING call, or a RUNNING one whose lease ran out: RUNNING, a new lease, attempts + 1. The
     * caller checks the attempts against the limit.
     */
    Optional<AiCall> claim(Instant now);

    /** Stores the outcome of a claimed call; false when the claim was lost (its lease ran out and it was claimed again). */
    boolean finish(AiCall c);

    /** DONE or FAILED calls no listener has taken yet, finished before the given time. */
    List<AiCall> undelivered(Instant finishedBefore);

    void delivered(String id);
}
