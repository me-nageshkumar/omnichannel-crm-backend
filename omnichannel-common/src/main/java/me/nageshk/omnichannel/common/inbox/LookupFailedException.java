package me.nageshk.omnichannel.common.inbox;

/** The device could not tell whether a number is on WhatsApp: it failed or did not answer in time. */
public class LookupFailedException extends RuntimeException {

    public static final String CODE = "LOOKUP_FAILED";

    public static final String DEFAULT_MESSAGE = "Could not check this number on WhatsApp, try again";

    public LookupFailedException() {
        this(DEFAULT_MESSAGE);
    }

    public LookupFailedException(String message) {
        super(message);
    }

    /** {@code cause} is what the device reported, kept for the logs; the message is what the person reads. */
    public LookupFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
