package me.nageshk.omnichannel.service.chatbot.run;

/** The run must stop here, not fail: for a do-not-contact contact (spec §5.7). */
public class StopRun extends RuntimeException {
    public StopRun(String reason) {
        super(reason);
    }
}
