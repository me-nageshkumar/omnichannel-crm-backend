package me.nageshk.omnichannel.service.billing.wallet;

import me.nageshk.omnichannel.service.inbox.InboxEvents;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** A Cloud message Meta failed after accepting it is not paid for. */
@Component
class CloudFailureRefunds {

    private final UsageCharger usage;

    CloudFailureRefunds(UsageCharger usage) {
        this.usage = usage;
    }

    @EventListener
    void on(InboxEvents.CloudMessageFailed e) {
        usage.giveBackForMessage(e.messageId(), "Meta could not deliver it");
    }
}
