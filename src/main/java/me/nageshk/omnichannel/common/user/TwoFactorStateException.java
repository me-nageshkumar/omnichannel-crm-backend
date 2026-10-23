package me.nageshk.omnichannel.common.user;

/** Two-factor is already in the requested state (enable when on, disable when off). Maps to 409. */
public class TwoFactorStateException extends RuntimeException {

    public static final String CODE = "TWO_FACTOR_STATE";

    public TwoFactorStateException(String message) {
        super(message);
    }
}
