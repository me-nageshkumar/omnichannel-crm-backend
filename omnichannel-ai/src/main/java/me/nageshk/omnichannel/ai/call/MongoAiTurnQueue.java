package me.nageshk.omnichannel.ai.call;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import me.nageshk.omnichannel.ai.config.AiProperties;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/** The turn queue on the {@code aiCalls} collection: claimed with one {@code findAndModify} under a lease. */
@Component
public class MongoAiTurnQueue implements AiTurnQueue {

    private static final Duration LEASE_SLACK = Duration.ofSeconds(30);

    private final MongoTemplate mongo;
    private final AiProperties props;
    private final Clock clock;

    public MongoAiTurnQueue(MongoTemplate mongo, AiProperties props, Clock clock) {
        this.mongo = mongo;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public String enqueue(AiCall c) {
        c.setStatus(AiCallStatus.PENDING);
        c.setAttempts(0);
        c.setLeaseUntil(null);
        return mongo.insert(c).getId();
    }

    @Override
    public Optional<AiCall> claim(Instant now) {
        Query q = new Query(new Criteria().orOperator(
                Criteria.where("status").is(AiCallStatus.PENDING),
                Criteria.where("status").is(AiCallStatus.RUNNING).and("leaseUntil").lt(now)))
                .with(Sort.by("createdAt"));
        Update u = new Update().set("status", AiCallStatus.RUNNING)
                .set("leaseUntil", now.plus(props.turnTimeout()).plus(LEASE_SLACK)).inc("attempts", 1);
        return Optional.ofNullable(mongo.findAndModify(q, u, FindAndModifyOptions.options().returnNew(true), AiCall.class));
    }

    @Override
    public boolean stage(AiCall c) {
        return mongo.updateFirst(held(c), result(c).set("stagedAt", c.getStagedAt()), AiCall.class)
                .getMatchedCount() > 0;
    }

    @Override
    public boolean finish(AiCall c) {
        Instant finishedAt = c.getFinishedAt() == null ? clock.instant() : c.getFinishedAt();
        Update u = result(c).set("status", c.getStatus()).set("leaseUntil", null).set("errorCode", c.getErrorCode())
                .set("error", c.getError()).set("finishedAt", finishedAt)
                .set("nextDeliveryAt", finishedAt.plus(props.redeliverAfter()));
        return mongo.updateFirst(held(c), u, AiCall.class).getMatchedCount() > 0;
    }

    /** The row while this claim holds it: still RUNNING, and no later claim since. */
    private static Query held(AiCall c) {
        return Query.query(Criteria.where("_id").is(c.getId()).and("status").is(AiCallStatus.RUNNING)
                .and("attempts").is(c.getAttempts()));
    }

    private static Update result(AiCall c) {
        return new Update().set("model", c.getModel()).set("reply", c.getReply()).set("finish", c.finishJson())
                .set("progress", c.progressJson())
                .set("toolCalls", c.getToolCalls()).set("usage", c.getUsage()).set("toolsUsed", c.isToolsUsed())
                .set("latencyMs", c.getLatencyMs()).set("stateBefore", c.getStateBefore())
                .set("transcriptSizeBefore", c.getTranscriptSizeBefore())
                .set("openCallIdsBefore", c.getOpenCallIdsBefore());
    }

    @Override
    public Optional<AiCall> claimRedelivery(Instant now) {
        Query q = Query.query(Criteria.where("deliveredAt").is(null).and("nextDeliveryAt").lte(now)
                .and("status").in(AiCallStatus.DONE, AiCallStatus.FAILED)
                .and("deliveryAttempts").lt(MAX_DELIVERY_ATTEMPTS))
                .with(Sort.by("nextDeliveryAt"));
        Update u = new Update().set("nextDeliveryAt", now.plus(props.redeliverAfter())).inc("deliveryAttempts", 1);
        return Optional.ofNullable(mongo.findAndModify(q, u, FindAndModifyOptions.options().returnNew(true), AiCall.class));
    }

    @Override
    public void delivered(String id) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id)), new Update().set("deliveredAt", clock.instant()),
                AiCall.class);
    }
}
