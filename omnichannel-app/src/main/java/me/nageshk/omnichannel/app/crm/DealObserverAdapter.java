package me.nageshk.omnichannel.app.crm;

import me.nageshk.omnichannel.crm.platform.DealLaneChange;
import me.nageshk.omnichannel.crm.platform.DealObserver;
import me.nageshk.omnichannel.db.automation.EventData;
import me.nageshk.omnichannel.service.automation.AutomationEventWriter;
import me.nageshk.omnichannel.service.automation.AutomationEventWriter.DealMove;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/** A deal's lane change becomes an automation event; a failure is logged and never reaches the CRM. */
@Component
class DealObserverAdapter implements DealObserver {

    private static final Logger LOG = LoggerFactory.getLogger(DealObserverAdapter.class);

    private final AutomationEventWriter writer;

    /** Lazy: the writer reaches the CRM (through AutomationCrm) and the CRM reaches this adapter. */
    DealObserverAdapter(@Lazy AutomationEventWriter writer) {
        this.writer = writer;
    }

    @Override
    public void laneChanged(DealLaneChange c) {
        try {
            writer.dealLaneChanged(new DealMove(c.workspaceId(), c.customerId(), c.dealId(), c.title(), c.status(),
                    c.amount(), c.currency(), c.ownerId(), c.lostReason(), c.pipelineId(), c.pipelineName(),
                    lane(c.from()), lane(c.to()), c.contactId(), c.phoneNumberId()));
        } catch (RuntimeException e) {
            LOG.warn("Deal {} lane change was not queued for automations", c.dealId(), e);
        }
    }

    private static EventData.LaneData lane(DealLaneChange.Lane l) {
        return l == null ? null : new EventData.LaneData(l.id(), l.name(), l.type());
    }
}
