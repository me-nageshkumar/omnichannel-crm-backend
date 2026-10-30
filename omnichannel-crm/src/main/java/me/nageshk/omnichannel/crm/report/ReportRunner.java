package me.nageshk.omnichannel.crm.report;

import com.mongodb.MongoExecutionTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.bson.Document;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.aggregation.AggregationOptions;

/** Runs a report aggregation with the 10 s time limit of spec C15; a timeout is 503 {@code REPORT_TIMEOUT}. */
public final class ReportRunner {

    static final Duration MAX_TIME = Duration.ofSeconds(10);

    private ReportRunner() {}

    public static List<Document> run(MongoTemplate mongo, String collection, List<AggregationOperation> ops) {
        Aggregation aggregation = Aggregation.newAggregation(ops)
                .withOptions(AggregationOptions.builder().maxTime(MAX_TIME).build());
        return guard(() -> mongo.aggregate(aggregation, collection, Document.class).getMappedResults());
    }

    /**
     * Runs a read under the report's rules: a time-limit error anywhere in the cause chain (Spring wraps the driver's
     * in an uncategorized {@code DataAccessException}) becomes {@link ReportErrors#timeout()}; anything else passes.
     */
    public static <T> T guard(Supplier<T> read) {
        try {
            return read.get();
        } catch (RuntimeException e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof MongoExecutionTimeoutException || t instanceof QueryTimeoutException) {
                    throw ReportErrors.timeout();
                }
            }
            throw e;
        }
    }
}
