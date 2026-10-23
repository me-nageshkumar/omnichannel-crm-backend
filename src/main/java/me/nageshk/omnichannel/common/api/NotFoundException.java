package me.nageshk.omnichannel.common.api;

/** A customer, workspace, team or user that does not exist or that the caller may not see: 404. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
