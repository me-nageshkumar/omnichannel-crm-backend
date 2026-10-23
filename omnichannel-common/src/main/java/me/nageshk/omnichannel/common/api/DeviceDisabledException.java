package me.nageshk.omnichannel.common.api;

/** The number, its brand or its customer is disabled, so it may not connect: 403 {@code DISABLED}. */
public class DeviceDisabledException extends RuntimeException {

    public static final String CODE = "DISABLED";

    public DeviceDisabledException() {
        super("This number, its brand or its company is disabled.");
    }
}
