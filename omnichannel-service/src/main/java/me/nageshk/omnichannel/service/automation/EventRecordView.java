package me.nageshk.omnichannel.service.automation;

import java.time.Instant;
import java.util.List;
import me.nageshk.omnichannel.db.automation.AutomationEventKind;
import me.nageshk.omnichannel.db.automation.EventData;
import me.nageshk.omnichannel.db.automation.EventOutcome;

/** One finished event of the history with the runs it caused. */
public record EventRecordView(String id, AutomationEventKind kind, Instant createdAt, Instant finishedAt,
        EventOutcome outcome, String error, String summary, RunView.Contact contact, RunView.Deal deal,
        EventData.NumberData number, boolean test, List<RunView> runs) {
}
