package me.nageshk.omnichannel.api;

/** An export would be larger than allowed: 400 {@code TOO_MANY_ROWS}; narrow the filter. */
public class TooManyRowsException extends RuntimeException {

    public static final String CODE = "TOO_MANY_ROWS";

    public TooManyRowsException(String message) {
        super(message);
    }
}
