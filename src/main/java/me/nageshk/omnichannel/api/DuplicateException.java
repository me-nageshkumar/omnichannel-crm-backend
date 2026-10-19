package me.nageshk.omnichannel.api;

/** A value that must be unique already exists: 409 {@code DUPLICATE} naming the offending field. */
public class DuplicateException extends RuntimeException {

    public static final String CODE = "DUPLICATE";

    private final String field;

    public DuplicateException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() { return field; }
}
