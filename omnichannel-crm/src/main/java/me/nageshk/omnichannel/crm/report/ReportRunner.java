package me.nageshk.omnichannel.crm.report;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import me.nageshk.omnichannel.crm.common.TimeLimited;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.aggregation.AggregationOptions;

/** Runs a report aggregation with the 10 s time limit of spec C15; a timeout is 503 {@code REPORT_TIMEOUT}. */
public final class ReportRunner {

    static final Duration MAX_TIME = TimeLimited.MAX_TIME;

    private ReportRunner() {}

    public static List<Document> run(MongoTemplate mongo, String collection, List<AggregationOperation> ops) {
        Aggregation aggregation = Aggregation.newAggregation(ops)
                .withOptions(AggregationOptions.builder().maxTime(MAX_TIME).build());
        return guard(() -> mongo.aggregate(aggregation, collection, Document.class).getMappedResults());
    }

    /** Runs a read under the report's rules: a time limit anywhere in the cause chain is {@link ReportErrors#timeout()}. */
    public static <T> T guard(Supplier<T> read) {
        return TimeLimited.guard(read, ReportErrors::timeout);
    }
}
