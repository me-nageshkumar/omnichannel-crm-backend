package me.nageshk.omnichannel.api;

/** A new password that fails the policy: 400 {@code WEAK_PASSWORD}. */
public class WeakPasswordException extends RuntimeException {

    public static final String CODE = "WEAK_PASSWORD";

    public WeakPasswordException(String message) {
        super(message);
    }
}
