package me.nageshk.omnichannel.meta.api;

import java.time.Duration;

/** A Cloud send Meta refused or could not take; never carries a token. */
public class CloudSendException extends RuntimeException {

    /** What happened, sorted by what the caller does: stop the number, wait, show Meta's reason, or not know. */
    public enum Kind { TOKEN_REFUSED, WINDOW_CLOSED, RATE_LIMITED, REFUSED, UNKNOWN }

    private final Kind kind;
    private final Integer metaCode;
    private final String title;
    private final String details;
    private final Duration retryAfter;

    public CloudSendException(Kind kind, Integer metaCode, String title, String details, Duration retryAfter) {
        super(details == null || details.isBlank() || details.equals(title) ? title : title + ": " + details);
        this.kind = kind;
        this.metaCode = metaCode;
        this.title = title;
        this.details = details;
        this.retryAfter = retryAfter;
    }

    public Kind kind() { return kind; }
    public Integer metaCode() { return metaCode; }
    public String title() { return title; }
    public String details() { return details; }
    public Duration retryAfter() { return retryAfter; }

    /** Meta answered and refused: the message did not go (everything but {@link Kind#UNKNOWN}). */
    public boolean definitelyNotSent() {
        return kind != Kind.UNKNOWN;
    }
}
