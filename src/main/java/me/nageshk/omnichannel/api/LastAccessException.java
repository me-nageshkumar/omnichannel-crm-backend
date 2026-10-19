package me.nageshk.omnichannel.api;

/**
 * Removing the user's role here would leave it with no role anywhere and no COMPANY role, a login
 * that can reach nothing: 400 {@code LAST_ACCESS}.
 */
public class LastAccessException extends RuntimeException {

    public static final String CODE = "LAST_ACCESS";

    public LastAccessException(String message) {
        super(message);
    }
}
