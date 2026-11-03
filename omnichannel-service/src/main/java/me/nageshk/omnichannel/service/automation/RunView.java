package me.nageshk.omnichannel.service.automation;

import java.time.Instant;
import java.util.List;
import me.nageshk.omnichannel.db.automation.AutomationEventKind;
import me.nageshk.omnichannel.db.automation.RunStatus;

/** One run of a rule for one event, as the console lists it. */
public record RunView(String id, String eventId, String ruleId, String ruleName, boolean test, RunStatus status,
        AutomationEventKind eventKind, String summary, Contact contact, Deal deal, Instant startedAt,
        Instant finishedAt, List<StepView> steps) {

    public record Contact(String id, String name, String phone) {
    }

    public record Deal(String id, String title) {
    }
}
