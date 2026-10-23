package me.nageshk.omnichannel.common.api;

/** The current user has no connected extension, or its WhatsApp is not in CONNECTED state. */
public class NoDeviceException extends RuntimeException {

    public static final String NO_DEVICE = "NO_DEVICE";
    public static final String WHATSAPP_NOT_READY = "WHATSAPP_NOT_READY";

    private final String code;

    public NoDeviceException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
