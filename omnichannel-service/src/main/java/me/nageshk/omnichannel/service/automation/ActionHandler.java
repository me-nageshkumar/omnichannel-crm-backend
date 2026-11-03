package me.nageshk.omnichannel.service.automation;

import me.nageshk.omnichannel.db.automation.AutomationAction;
import me.nageshk.omnichannel.db.automation.AutomationActionType;

/** Runs one kind of rule action. */
public interface ActionHandler {

    AutomationActionType type();

    ActionResult run(ActionContext ctx, AutomationAction action);
}
