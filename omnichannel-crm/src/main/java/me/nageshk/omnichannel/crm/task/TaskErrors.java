package me.nageshk.omnichannel.crm.task;

import me.nageshk.omnichannel.common.api.CodedException;
import me.nageshk.omnichannel.crm.common.CrmErrors;

/** The coded 400s of tasks (spec E C9, C14). */
public final class TaskErrors {

    public static final String NOT_OPEN = "TASK_NOT_OPEN";
    public static final String NOT_RECURRING = "NOT_RECURRING";

    private TaskErrors() {}

    public static CodedException notOpen() {
        return CrmErrors.badRequest(NOT_OPEN, "Only an open task can be changed this way");
    }

    public static CodedException notRecurring() {
        return CrmErrors.badRequest(NOT_RECURRING, "Only a repeating task can be skipped");
    }
}
