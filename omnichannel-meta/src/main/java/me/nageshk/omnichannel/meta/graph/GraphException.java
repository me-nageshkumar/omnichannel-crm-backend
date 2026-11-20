package me.nageshk.omnichannel.meta.graph;

import java.time.Duration;

/** A Graph API call that failed, sorted by what the caller should do about it. Never carries a token. */
public class GraphException extends RuntimeException {

    /** What kind of failure: the caller's handling follows from it, not from Meta's numeric code. */
    public enum Kind { AUTH, WINDOW_CLOSED, RATE_LIMIT, BAD_REQUEST, NOT_FOUND, UNAVAILABLE, TIMEOUT }

    private final Kind kind;
    private final int httpStatus;
    private final Integer metaCode;
    private final String title;
    private final String details;
    private final Duration retryAfter;

    public GraphException(Kind kind, int httpStatus, Integer metaCode, String title, String details,
            Duration retryAfter) {
        super(message(title, details, kind));
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.metaCode = metaCode;
        this.title = title;
        this.details = details;
        this.retryAfter = retryAfter;
    }

    private static String message(String title, String details, Kind kind) {
        String t = title == null || title.isBlank() ? "Meta's API failed (" + kind + ")" : title;
        return details == null || details.isBlank() || details.equals(title) ? t : t + ": " + details;
    }

    public Kind kind() { return kind; }
    public int httpStatus() { return httpStatus; }
    public Integer metaCode() { return metaCode; }
    public String title() { return title; }
    public String details() { return details; }
    public Duration retryAfter() { return retryAfter; }

    /** A 4xx Meta answered: the request was refused, nothing was sent. */
    public boolean refused() {
        return httpStatus >= 400 && httpStatus < 500;
    }
}
