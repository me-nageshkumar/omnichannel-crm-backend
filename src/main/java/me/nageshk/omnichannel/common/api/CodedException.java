package me.nageshk.omnichannel.common.api;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** A failure with its own status and code: the body is {@code {code, message}} plus {@code field} and any extras. */
public class CodedException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String field;
    private final Map<String, String> extra;

    public CodedException(HttpStatus status, String code, String field, String message) {
        this(status, code, field, message, Map.of());
    }

    public CodedException(HttpStatus status, String code, String field, String message, Map<String, String> extra) {
        super(message);
        this.status = status;
        this.code = code;
        this.field = field;
        this.extra = Map.copyOf(extra);
    }

    public HttpStatus status() { return status; }

    public String code() { return code; }

    /** The request field at fault, or null. */
    public String field() { return field; }

    public Map<String, String> extra() { return extra; }
}
