package me.nageshk.omnichannel.crm.task;

import java.util.List;
import me.nageshk.omnichannel.crm.access.CrmAccess;
import me.nageshk.omnichannel.crm.task.dto.ReminderView;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/crm/reminders")
public class CrmReminderController {

    private static final String VIEW = "@access.has(#workspaceId, 'crm:view')";

    private final CrmAccess crmAccess;
    private final ReminderService service;

    CrmReminderController(CrmAccess crmAccess, ReminderService service) {
        this.crmAccess = crmAccess;
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(VIEW)
    public List<ReminderView> due(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId) {
        return service.due(crmAccess.resolve(me, workspaceId));
    }

    @PostMapping("/{taskId}/seen")
    @PreAuthorize(VIEW)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void seen(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String taskId) {
        service.seen(crmAccess.resolve(me, workspaceId), taskId);
    }
}
