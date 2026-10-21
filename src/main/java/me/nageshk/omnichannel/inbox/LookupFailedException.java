package me.nageshk.omnichannel.inbox;

/** The device could not tell whether a number is on WhatsApp: it failed or did not answer in time. */
public class LookupFailedException extends RuntimeException {

    public static final String CODE = "LOOKUP_FAILED";

    public LookupFailedException() {
        this("Could not check this number on WhatsApp, try again");
    }

    public LookupFailedException(String message) {
        super(message);
    }
}
