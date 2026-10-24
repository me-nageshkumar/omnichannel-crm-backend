package me.nageshk.omnichannel.crm.task;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.crm.access.CrmAccess;
import me.nageshk.omnichannel.crm.common.CrmPage;
import me.nageshk.omnichannel.crm.common.CrmTarget;
import me.nageshk.omnichannel.crm.common.TargetType;
import me.nageshk.omnichannel.crm.task.dto.TaskCounts;
import me.nageshk.omnichannel.crm.task.dto.TaskRequest;
import me.nageshk.omnichannel.crm.task.dto.TaskView;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/crm/tasks")
public class CrmTaskController {

    private static final String VIEW = "@access.has(#workspaceId, 'crm:view')";
    private static final String EDIT = VIEW + " and @access.has(#workspaceId, 'crm:edit')";

    private final CrmAccess crmAccess;
    private final TaskService service;

    CrmTaskController(CrmAccess crmAccess, TaskService service) {
        this.crmAccess = crmAccess;
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(VIEW)
    public CrmPage<TaskView> forTarget(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @RequestParam String targetType, @RequestParam String targetId,
            @RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        CrmTarget target = new CrmTarget(TaskService.parse(TargetType.class, targetType, "targetType"), targetId);
        return service.forTarget(crmAccess.resolve(me, workspaceId), target, status, page, size);
    }

    @GetMapping("/mine")
    @PreAuthorize(VIEW)
    public List<TaskView> mine(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @RequestParam String bucket, @RequestParam(defaultValue = "UTC") String tz,
            @RequestParam(required = false) String assigneeId, @RequestParam(required = false) Integer limit) {
        return service.mine(crmAccess.resolve(me, workspaceId), TaskService.parse(Bucket.class, bucket, "bucket"),
                zone(tz), assigneeId, limit);
    }

    @GetMapping("/mine/counts")
    @PreAuthorize(VIEW)
    public TaskCounts counts(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @RequestParam(defaultValue = "UTC") String tz, @RequestParam(required = false) String assigneeId) {
        return service.counts(crmAccess.resolve(me, workspaceId), zone(tz), assigneeId);
    }

    @PostMapping
    @PreAuthorize(EDIT)
    public TaskView create(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @RequestBody TaskRequest request) {
        return service.create(crmAccess.resolve(me, workspaceId), request);
    }

    @PutMapping("/{id}")
    @PreAuthorize(EDIT)
    public TaskView update(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id, @RequestBody TaskRequest request) {
        return service.update(crmAccess.resolve(me, workspaceId), id, request);
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize(EDIT)
    public TaskView complete(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id) {
        return service.complete(crmAccess.resolve(me, workspaceId), id);
    }

    @PostMapping("/{id}/reopen")
    @PreAuthorize(EDIT)
    public TaskView reopen(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id) {
        return service.reopen(crmAccess.resolve(me, workspaceId), id);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(EDIT)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id) {
        service.delete(crmAccess.resolve(me, workspaceId), id);
    }

    private static ZoneId zone(String tz) {
        try {
            return ZoneId.of(tz.trim());
        } catch (DateTimeException e) {
            throw new ValidationException("tz", "is not a time zone");
        }
    }
}
