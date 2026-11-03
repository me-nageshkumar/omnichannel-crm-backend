package me.nageshk.omnichannel.service.automation;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.db.automation.AutomationAction;
import me.nageshk.omnichannel.db.automation.AutomationActionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Runs one action through the handler of its type; whatever a handler throws fails that step alone. */
@Component
public class ActionRunner {

    private static final Logger log = LoggerFactory.getLogger(ActionRunner.class);

    private final Map<AutomationActionType, ActionHandler> handlers = new EnumMap<>(AutomationActionType.class);

    ActionRunner(List<ActionHandler> handlers) {
        for (ActionHandler h : handlers) {
            ActionHandler before = this.handlers.putIfAbsent(h.type(), h);
            if (before != null) throw new IllegalStateException("two handlers for " + h.type());
        }
    }

    public ActionResult run(ActionContext ctx, AutomationAction a) {
        ActionHandler h = a == null || a.type() == null ? null : handlers.get(a.type());
        if (h == null) return ActionResult.failed("Unknown action");
        try {
            ActionResult r = h.run(ctx, a);
            return r == null ? ActionResult.failed("The action gave no result") : r;
        } catch (RuntimeException e) {
            log.warn("automation action {} of rule {} failed on event {}", a.type(), ctx.ruleId(), ctx.eventId(), e);
            return ActionResult.failed("Unexpected error: " + e.getClass().getSimpleName());
        }
    }
}
