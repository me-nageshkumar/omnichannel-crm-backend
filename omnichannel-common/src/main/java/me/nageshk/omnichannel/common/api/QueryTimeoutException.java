package me.nageshk.omnichannel.common.api;

/** A filter query ran over its time limit: 504 {@code QUERY_TIMEOUT}; the filter should be narrowed. */
public class QueryTimeoutException extends RuntimeException {

    public static final String CODE = "QUERY_TIMEOUT";
    public static final String MESSAGE = "This filter takes too long to run. Make it more specific.";

    public QueryTimeoutException(Throwable cause) {
        super(MESSAGE, cause);
    }
}
