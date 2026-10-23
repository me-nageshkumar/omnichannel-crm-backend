package me.nageshk.omnichannel.crm.activity;

import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.crm.access.CrmAccess;
import me.nageshk.omnichannel.crm.activity.dto.ActivityView;
import me.nageshk.omnichannel.crm.activity.dto.LogRequest;
import me.nageshk.omnichannel.crm.common.CrmPage;
import me.nageshk.omnichannel.crm.common.TargetType;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/crm/activities")
public class CrmActivityController {

    private static final String VIEW = "@access.has(#workspaceId, 'crm:view')";
    private static final String EDIT = VIEW + " and @access.has(#workspaceId, 'crm:edit')";

    private final CrmAccess crmAccess;
    private final TimelineService timeline;

    CrmActivityController(CrmAccess crmAccess, TimelineService timeline) {
        this.crmAccess = crmAccess;
        this.timeline = timeline;
    }

    @GetMapping
    @PreAuthorize(VIEW)
    public CrmPage<ActivityView> list(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @RequestParam String targetType, @RequestParam String targetId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        TargetType type;
        try {
            type = TargetType.valueOf(targetType.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("targetType", "must be CONTACT, COMPANY or DEAL");
        }
        return timeline.timeline(crmAccess.resolve(me, workspaceId), type, targetId, page, size);
    }

    @PostMapping("/log")
    @PreAuthorize(EDIT)
    public ActivityView log(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @RequestBody LogRequest request) {
        return timeline.logActivity(crmAccess.resolve(me, workspaceId), request);
    }
}
