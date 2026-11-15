package me.nageshk.omnichannel.ai.call;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
    private static final int REDELIVER_BATCH = 100;

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
    public boolean finish(AiCall c) {
        Query q = Query.query(Criteria.where("_id").is(c.getId()).and("status").is(AiCallStatus.RUNNING)
                .and("attempts").is(c.getAttempts()));
        Update u = new Update().set("status", c.getStatus()).set("leaseUntil", null).set("model", c.getModel())
                .set("reply", c.getReply()).set("finish", c.getFinish()).set("toolCalls", c.getToolCalls())
                .set("usage", c.getUsage()).set("toolsUsed", c.isToolsUsed()).set("latencyMs", c.getLatencyMs())
                .set("errorCode", c.getErrorCode()).set("error", c.getError()).set("stateBefore", c.getStateBefore())
                .set("transcriptSizeBefore", c.getTranscriptSizeBefore())
                .set("openCallIdsBefore", c.getOpenCallIdsBefore()).set("finishedAt", c.getFinishedAt());
        return mongo.updateFirst(q, u, AiCall.class).getModifiedCount() > 0;
    }

    @Override
    public List<AiCall> undelivered(Instant finishedBefore) {
        Query q = Query.query(Criteria.where("status").in(AiCallStatus.DONE, AiCallStatus.FAILED)
                .and("deliveredAt").is(null).and("finishedAt").lt(finishedBefore))
                .with(Sort.by("finishedAt")).limit(REDELIVER_BATCH);
        return mongo.find(q, AiCall.class);
    }

    @Override
    public void delivered(String id) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id)), new Update().set("deliveredAt", clock.instant()),
                AiCall.class);
    }
}
