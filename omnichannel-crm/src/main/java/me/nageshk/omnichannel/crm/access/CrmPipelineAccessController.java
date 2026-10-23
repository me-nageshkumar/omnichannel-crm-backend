package me.nageshk.omnichannel.crm.access;

import me.nageshk.omnichannel.crm.access.dto.UserPipelinesRequest;
import me.nageshk.omnichannel.crm.access.dto.UserPipelinesView;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/crm/users/{userId}/pipelines")
public class CrmPipelineAccessController {

    private final CrmAccess crmAccess;
    private final PipelineAccessService service;

    CrmPipelineAccessController(CrmAccess crmAccess, PipelineAccessService service) {
        this.crmAccess = crmAccess;
        this.service = service;
    }

    /** The service demands {@code crm:settings} for anyone but the caller. */
    @GetMapping
    @PreAuthorize("@access.has(#workspaceId, 'crm:view')")
    public UserPipelinesView get(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String userId) {
        return service.get(crmAccess.resolve(me, workspaceId), userId);
    }

    @PutMapping
    @PreAuthorize("@access.has(#workspaceId, 'crm:view') and @access.has(#workspaceId, 'crm:settings')")
    public UserPipelinesView put(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String userId, @RequestBody UserPipelinesRequest request) {
        return service.put(crmAccess.resolve(me, workspaceId), userId, request.pipelineIds());
    }
}
