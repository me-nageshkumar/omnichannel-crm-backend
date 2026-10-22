package me.nageshk.omnichannel.contact;

import com.mongodb.MongoExecutionTimeoutException;
import java.time.Duration;
import java.util.function.Supplier;
import me.nageshk.omnichannel.api.QueryTimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Time limits for contact filter queries, and the translation of a time-limit error into a 504. */
@Component
public class QueryTimeouts {

    private final Duration count;
    private final Duration search;
    private final Duration materialise;

    public QueryTimeouts(@Value("${omnichannel.contacts.query-timeout.count:5s}") Duration count,
            @Value("${omnichannel.contacts.query-timeout.search:10s}") Duration search,
            @Value("${omnichannel.contacts.query-timeout.materialise:30s}") Duration materialise) {
        this.count = count;
        this.search = search;
        this.materialise = materialise;
    }

    /** Counting and sampling for a preview. */
    public Duration count() {
        return count;
    }

    /** A page of the contact list. */
    public Duration search() {
        return search;
    }

    /** Collecting a campaign's audience. */
    public Duration materialise() {
        return materialise;
    }

    /**
     * Runs a query; a time-limit error anywhere in the cause chain (Spring wraps it in a {@code DataAccessException})
     * comes out as {@link QueryTimeoutException}, every other failure unchanged.
     */
    public static <T> T guarded(Supplier<T> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof MongoExecutionTimeoutException) throw new QueryTimeoutException(e);
            }
            throw e;
        }
    }
}
