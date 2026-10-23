package me.nageshk.omnichannel.common.inbox;

/** Starting a chat with a number whose chat belongs to someone else, outside the caller's inbox scope. */
public class ChatTakenException extends RuntimeException {

    public static final String CODE = "CHAT_TAKEN";

    public ChatTakenException() {
        super("This number already has a chat assigned to someone else");
    }
}
