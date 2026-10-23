package me.nageshk.omnichannel.common.inbox;

/** A reply to a closed chat; the console reopens it first. */
public class ChatClosedException extends RuntimeException {

    public static final String CODE = "CHAT_CLOSED";

    public ChatClosedException() {
        super("This chat is closed");
    }
}
