package me.nageshk.omnichannel.service.automation;

/** An action cannot run; the message is the reason shown in the run's history. */
public class AutomationActionFailed extends RuntimeException {

    public AutomationActionFailed(String reason) {
        super(reason);
    }
}
