package me.nageshk.omnichannel.ai.knowledge;

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

/**
 * The knowledge queue on the {@code aiKnowledgeSources} collection: the next source with work to do is claimed with
 * one {@code findAndModify} under a lease, newest first. The claim leaves the row's version alone, so a change made
 * through the API while the worker holds the row still wins over the worker's save.
 */
@Component
class KnowledgeQueue {

    private final MongoTemplate mongo;
    private final AiProperties props;

    KnowledgeQueue(MongoTemplate mongo, AiProperties props) {
        this.mongo = mongo;
        this.props = props;
    }

    /**
     * Takes a source that is QUEUED or UPLOADING, INDEXING and due for a check, or REMOVING, whose lease is expired or
     * absent; sets the lease and counts the attempt.
     */
    Optional<KnowledgeSource> claim(Instant now) {
        Criteria free = new Criteria().orOperator(Criteria.where("leaseUntil").is(null),
                Criteria.where("leaseUntil").lt(now));
        Query q = new Query(new Criteria().andOperator(free, new Criteria().orOperator(
                Criteria.where("state").in(SourceState.QUEUED, SourceState.UPLOADING, SourceState.REMOVING),
                Criteria.where("state").is(SourceState.INDEXING).and("nextCheckAt").lte(now))))
                .with(Sort.by(Sort.Direction.DESC, "addedAt"));
        Update u = new Update().set("leaseUntil", now.plus(props.knowledge().lease())).inc("attempts", 1);
        return Optional.ofNullable(
                mongo.findAndModify(q, u, FindAndModifyOptions.options().returnNew(true), KnowledgeSource.class));
    }
}
