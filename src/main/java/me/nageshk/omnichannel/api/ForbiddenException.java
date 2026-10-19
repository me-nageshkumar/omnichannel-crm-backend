package me.nageshk.omnichannel.api;

/** A resource the caller may see but may not change: 403. */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
