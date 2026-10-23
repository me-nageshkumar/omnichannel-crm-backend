package me.nageshk.omnichannel.common.api;

/** An unknown, used or expired connect code: 400 {@code INVALID_CONNECT_CODE}. */
public class InvalidConnectCodeException extends RuntimeException {

    public static final String CODE = "INVALID_CONNECT_CODE";

    public InvalidConnectCodeException() {
        super("Invalid or expired connect code.");
    }
}
