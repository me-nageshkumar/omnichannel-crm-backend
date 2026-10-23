package me.nageshk.omnichannel.common.user;

/** A wrong one-time code on a self-service two-factor call; maps to 400, not 401, so the session survives. */
public class InvalidOtpException extends RuntimeException {

    public static final String CODE = "INVALID_OTP";

    public InvalidOtpException() {
        super("the one-time code is not valid");
    }
}
