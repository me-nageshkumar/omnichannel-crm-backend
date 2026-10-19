package me.nageshk.omnichannel.api;

/** A role still held by someone cannot be deleted: 409 {@code ROLE_IN_USE}. */
public class RoleInUseException extends RuntimeException {

    public static final String CODE = "ROLE_IN_USE";

    public RoleInUseException(String message) {
        super(message);
    }
}
