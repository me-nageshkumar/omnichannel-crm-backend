package me.nageshk.omnichannel.crm.job;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import me.nageshk.omnichannel.crm.pipeline.CrmPipeline;
import me.nageshk.omnichannel.crm.platform.BrandDirectory;
import me.nageshk.omnichannel.crm.settings.CrmSettings;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Flags quiet deals (spec §6.8): per brand with the CRM on and per pipeline, an OPEN deal whose {@code lastActivityAt}
 * (else {@code stageEnteredAt}, else {@code creationTime}) is older than the pipeline's rotting days (else the
 * brand's) is {@code rotting}, every other OPEN deal is not; no days means never. Closed deals are left alone, and
 * the flag is written by collection name so no version is bumped and no activity recorded.
 */
@Component
public class DealRottingJob {

    private static final String DEALS = "crmDeals";

    private final MongoTemplate mongo;
    private final BrandDirectory brands;
    private final Clock clock;

    DealRottingJob(MongoTemplate mongo, BrandDirectory brands, Clock clock) {
        this.mongo = mongo;
        this.brands = brands;
        this.clock = clock;
    }

    /** The number of deals newly flagged. */
    public int run() {
        Instant now = Instant.now(clock);
        long flagged = 0;
        for (String ws : brands.allBrandIds()) {
            CrmSettings s = mongo.findOne(new Query(Criteria.where("workspaceId").is(ws)), CrmSettings.class);
            if (s == null || !s.isEnabled()) continue;
            for (CrmPipeline p : mongo.find(new Query(Criteria.where("workspaceId").is(ws)), CrmPipeline.class)) {
                Integer days = p.getRottingDays() != null ? p.getRottingDays() : s.getDealRottingDays();
                Criteria open = Criteria.where("workspaceId").is(ws).and("pipelineId").is(p.getId()).and("status")
                        .is("OPEN");
                if (days == null || days <= 0) {
                    mongo.updateMulti(new Query(open).addCriteria(Criteria.where("rotting").is(true)),
                            new Update().set("rotting", false), DEALS);
                    continue;
                }
                Criteria quiet = quiet(now.minus(Duration.ofDays(days)));
                flagged += mongo.updateMulti(new Query(new Criteria().andOperator(open, quiet,
                        Criteria.where("rotting").ne(true))), new Update().set("rotting", true), DEALS)
                        .getModifiedCount();
                mongo.updateMulti(new Query(new Criteria().andOperator(open, new Criteria().norOperator(quiet),
                        Criteria.where("rotting").is(true))), new Update().set("rotting", false), DEALS);
            }
        }
        return (int) flagged;
    }

    /** {@code coalesce(lastActivityAt, stageEnteredAt, creationTime) < cutoff}. */
    private static Criteria quiet(Instant cutoff) {
        return new Criteria().orOperator(Criteria.where("lastActivityAt").lt(cutoff),
                Criteria.where("lastActivityAt").is(null).and("stageEnteredAt").lt(cutoff),
                Criteria.where("lastActivityAt").is(null).and("stageEnteredAt").is(null).and("creationTime")
                        .lt(cutoff));
    }
}
