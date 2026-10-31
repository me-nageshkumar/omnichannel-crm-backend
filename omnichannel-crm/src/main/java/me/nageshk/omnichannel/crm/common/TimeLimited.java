package me.nageshk.omnichannel.crm.common;

import com.mongodb.MongoExecutionTimeoutException;
import java.time.Duration;
import java.util.function.Supplier;
import me.nageshk.omnichannel.common.api.CodedException;
import org.springframework.dao.QueryTimeoutException;

/**
 * The CRM's one rule for slow reads: every report and segment query runs under {@link #MAX_TIME}, and a time-limit
 * error anywhere in the cause chain (Spring wraps the driver's in an uncategorized {@code DataAccessException})
 * becomes the caller's own 503; anything else passes through untouched.
 */
public final class TimeLimited {

    public static final Duration MAX_TIME = Duration.ofSeconds(10);

    private TimeLimited() {}

    public static <T> T guard(Supplier<T> read, Supplier<CodedException> onTimeout) {
        try {
            return read.get();
        } catch (RuntimeException e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof MongoExecutionTimeoutException || t instanceof QueryTimeoutException) {
                    throw onTimeout.get();
                }
            }
            throw e;
        }
    }
}
