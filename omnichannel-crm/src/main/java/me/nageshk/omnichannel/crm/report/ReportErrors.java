package me.nageshk.omnichannel.crm.report;

import java.util.Map;
import me.nageshk.omnichannel.common.api.CodedException;
import org.springframework.http.HttpStatus;

/** The two errors the reports answer with. */
public final class ReportErrors {

    public static final String INVALID_FILTER = "INVALID_REPORT_FILTER";
    public static final String TIMEOUT = "REPORT_TIMEOUT";

    private ReportErrors() {}

    /** 400: {@code field} names the request field at fault. */
    public static CodedException invalid(String field, String message) {
        return new CodedException(HttpStatus.BAD_REQUEST, INVALID_FILTER, null, message, Map.of("field", field));
    }

    /** 503: the aggregation ran past its time limit. */
    public static CodedException timeout() {
        return new CodedException(HttpStatus.SERVICE_UNAVAILABLE, TIMEOUT, null,
                "The report took too long; narrow the date range and try again");
    }
}
