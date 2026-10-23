package me.nageshk.omnichannel.common.api;

/** The user already has a role in the workspace; change it instead: 409 {@code ALREADY_IN_WORKSPACE}. */
public class AlreadyInWorkspaceException extends RuntimeException {

    public static final String CODE = "ALREADY_IN_WORKSPACE";

    public AlreadyInWorkspaceException(String message) {
        super(message);
    }
}
