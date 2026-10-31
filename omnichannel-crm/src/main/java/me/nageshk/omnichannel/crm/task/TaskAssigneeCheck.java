package me.nageshk.omnichannel.crm.task;

import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.crm.access.CrmAccess;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.common.CrmTarget;
import org.springframework.stereotype.Component;

/**
 * Checks that a task can be handed to a user (spec E C2): a user of the brand who can use the CRM and can see the
 * task's target under their own pipeline access. Assigning to yourself is never checked. Every failure is a field
 * error on {@code assigneeId}, which the console shows under the Assignee field.
 */
@Component
public class TaskAssigneeCheck {

    private final CrmAccess crmAccess;
    private final TaskAccess targetAccess;

    TaskAssigneeCheck(CrmAccess crmAccess, TaskAccess targetAccess) {
        this.crmAccess = crmAccess;
        this.targetAccess = targetAccess;
    }

    public void assertAssignable(CrmContext ctx, String assigneeId, CrmTarget target) {
        if (assigneeId == null || assigneeId.equals(ctx.userId())) return;
        CrmContext assignee = crmAccess.resolveFor(ctx, assigneeId)
                .orElseThrow(() -> new ValidationException("assigneeId", "is not a user of this brand"));
        if (!assignee.can("crm:view")) throw new ValidationException("assigneeId", "cannot use the CRM");
        try {
            targetAccess.assertVisible(assignee, target);
        } catch (NotFoundException e) {
            throw new ValidationException("assigneeId", "cannot see this record");
        }
    }
}
