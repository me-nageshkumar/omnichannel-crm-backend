package me.nageshk.omnichannel.crm.pipeline;

import java.util.List;
import me.nageshk.omnichannel.crm.access.CrmAccess;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.pipeline.dto.PipelineRequest;
import me.nageshk.omnichannel.crm.pipeline.dto.PipelineView;
import me.nageshk.omnichannel.crm.pipeline.dto.StagesRequest;
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
@RequestMapping("/api/workspaces/{workspaceId}/crm/pipelines")
public class CrmPipelineController {

    private static final String VIEW = "@access.has(#workspaceId, 'crm:view')";
    private static final String WRITE = VIEW + " and @access.has(#workspaceId, 'crm:settings')";

    private final CrmAccess crmAccess;
    private final CrmPipelineService service;

    CrmPipelineController(CrmAccess crmAccess, CrmPipelineService service) {
        this.crmAccess = crmAccess;
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(VIEW)
    public List<PipelineView> list(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId) {
        return service.list(crmAccess.resolve(me, workspaceId)).stream().map(PipelineView::of).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize(VIEW)
    public PipelineView get(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id) {
        return PipelineView.of(service.get(crmAccess.resolve(me, workspaceId), id));
    }

    @PostMapping
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public PipelineView create(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @RequestBody PipelineRequest request) {
        CrmContext ctx = crmAccess.resolve(me, workspaceId);
        return PipelineView.of(service.create(ctx, request));
    }

    @PutMapping("/{id}")
    @PreAuthorize(WRITE)
    public PipelineView update(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id, @RequestBody PipelineRequest request) {
        return PipelineView.of(service.update(crmAccess.resolve(me, workspaceId), id, request));
    }

    @PutMapping("/{id}/stages")
    @PreAuthorize(WRITE)
    public PipelineView replaceStages(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id, @RequestBody StagesRequest request) {
        return PipelineView.of(
                service.replaceStages(crmAccess.resolve(me, workspaceId), id, request.stages(), request.version()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id, @RequestParam(required = false) String moveDealsToPipelineId) {
        service.delete(crmAccess.resolve(me, workspaceId), id, moveDealsToPipelineId);
    }
}
