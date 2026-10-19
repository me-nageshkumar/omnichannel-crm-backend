package me.nageshk.omnichannel.api;

/** A missing, expired or wrong-purpose bearer token on an endpoint that reads its own token: 401 {@code INVALID_TOKEN}. */
public class InvalidTokenException extends RuntimeException {

    public static final String CODE = "INVALID_TOKEN";

    public InvalidTokenException(String message) {
        super(message);
    }
}
