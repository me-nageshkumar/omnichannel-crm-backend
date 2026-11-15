package me.nageshk.omnichannel.ai.provider;

import java.time.Duration;

public class AiProviderException extends RuntimeException {

    public enum Code {
        AUTH, RATE_LIMIT, TIMEOUT, BAD_REQUEST, CONTEXT_TOO_LONG, STATE_EXPIRED, APPROVAL_REQUIRED, UNAVAILABLE
    }

    private final Code code;
    private final Duration retryAfter;

    public AiProviderException(Code code, String message, Duration retryAfter) {
        super(message);
        this.code = code;
        this.retryAfter = retryAfter;
    }

    public AiProviderException(Code code, String message) {
        this(code, message, null);
    }

    public Code code() {
        return code;
    }

    /** Null when the provider gave none. */
    public Duration retryAfter() {
        return retryAfter;
    }
}
