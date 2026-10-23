package me.nageshk.omnichannel.common.api;

/** A user named for a workspace has no access to it, or is not a user of the caller's customer: 400 {@code NOT_IN_WORKSPACE}. */
public class NotInWorkspaceException extends RuntimeException {

    public static final String CODE = "NOT_IN_WORKSPACE";

    public NotInWorkspaceException(String message) {
        super(message);
    }
}
