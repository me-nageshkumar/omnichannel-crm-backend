package me.nageshk.omnichannel.api;

import java.util.List;

/** An authority held by roles cannot be deleted or change level: 409 {@code AUTHORITY_IN_USE} naming the roles. */
public class AuthorityInUseException extends RuntimeException {

    public static final String CODE = "AUTHORITY_IN_USE";

    private final List<String> roles;

    public AuthorityInUseException(String message, List<String> roles) {
        super(message);
        this.roles = List.copyOf(roles);
    }

    public List<String> roles() { return roles; }
}
