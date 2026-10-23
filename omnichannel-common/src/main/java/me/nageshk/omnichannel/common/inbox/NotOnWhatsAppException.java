package me.nageshk.omnichannel.common.inbox;

/** Starting a chat with a phone number that WhatsApp does not know. */
public class NotOnWhatsAppException extends RuntimeException {

    public static final String CODE = "NOT_ON_WHATSAPP";

    public NotOnWhatsAppException() {
        super("This number is not on WhatsApp");
    }
}
