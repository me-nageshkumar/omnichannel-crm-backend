package me.nageshk.omnichannel.common.api;

import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * A free-form message to a Cloud API number's customer outside Meta's 24-hour window: only a WhatsApp template may go
 * (409 {@code WINDOW_CLOSED}, with {@code closesAt} when the customer ever wrote).
 */
public class WindowClosedException extends CodedException {

    public static final String CODE = "WINDOW_CLOSED";

    public WindowClosedException(Instant closedAt) {
        super(HttpStatus.CONFLICT, CODE, "window", "The 24-hour window closed"
                + (closedAt == null ? "" : " at " + closedAt) + "; send a WhatsApp template",
                closedAt == null ? Map.of() : Map.of("closesAt", closedAt.toString()));
    }
}
