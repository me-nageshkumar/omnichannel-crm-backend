package me.nageshk.omnichannel.service.chatbot.run;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import me.nageshk.omnichannel.common.config.OmnichannelProperties;
import me.nageshk.omnichannel.db.chatbot.ChatbotRun;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunStatus;
import me.nageshk.omnichannel.db.chatbot.TaskKind;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/** Atomic claims on runs (spec §5.2, §5.3): an answer or a resume touches a run exactly once. */
@Component
public class RunClaims {

    private final MongoTemplate mongo;
    private final OmnichannelProperties props;
    private final Clock clock;

    public RunClaims(MongoTemplate mongo, OmnichannelProperties props, Clock clock) {
        this.mongo = mongo;
        this.props = props;
        this.clock = clock;
    }

    /** WAITING on {@code (kind, key)} → RUNNING under a fresh lease; empty when no run waits on that pair. */
    public Optional<ChatbotRun> claimForAnswer(TaskKind kind, String key) {
        Query q = new Query(Criteria.where("waiting.kind").is(kind.name()).and("waiting.key").is(key)
                .and("status").is(ChatbotRunStatus.WAITING.name()));
        Update u = new Update().set("status", ChatbotRunStatus.RUNNING.name()).set("leaseUntil", lease()).set("lastAnswerKey", key);
        return Optional.ofNullable(mongo.findAndModify(q, u, FindAndModifyOptions.options().returnNew(true), ChatbotRun.class));
    }

    /** RUNNING with a retry due → RUNNING under a fresh lease and no {@code retryAt}, so one tick retries once. */
    public Optional<ChatbotRun> claimForRetry(String runId) {
        Query q = new Query(Criteria.where("_id").is(runId).and("status").is(ChatbotRunStatus.RUNNING.name())
                .and("retryAt").lte(clock.instant()));
        Update u = new Update().set("leaseUntil", lease()).unset("retryAt");
        return Optional.ofNullable(mongo.findAndModify(q, u, FindAndModifyOptions.options().returnNew(true), ChatbotRun.class));
    }

    /** Runs due for a retry. */
    public List<ChatbotRun> retriesDue() {
        return mongo.find(new Query(Criteria.where("status").is(ChatbotRunStatus.RUNNING.name())
                .and("retryAt").lte(clock.instant())), ChatbotRun.class);
    }

    /** RUNNING runs whose lease ran out: the app stopped mid-node; never re-run. */
    public List<ChatbotRun> stale(Instant now) {
        return mongo.find(new Query(Criteria.where("status").is(ChatbotRunStatus.RUNNING.name())
                .and("leaseUntil").lt(now).and("retryAt").exists(false)), ChatbotRun.class);
    }

    public Instant lease() {
        return clock.instant().plus(props.chatbot().lease());
    }
}
