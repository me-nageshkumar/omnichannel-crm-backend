package me.nageshk.omnichannel.service.chatbot.run;

/** A send never reached the extension; the runner tries the node again after a short wait (spec §5.7). */
public class RetryLater extends RuntimeException {
    public RetryLater(String message) {
        super(message);
    }
}
