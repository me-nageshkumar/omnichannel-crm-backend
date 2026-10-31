package me.nageshk.omnichannel.crm.segment;

import java.util.List;
import me.nageshk.omnichannel.crm.access.CrmAccess;
import me.nageshk.omnichannel.crm.segment.dto.SegmentCatalogView;
import me.nageshk.omnichannel.crm.segment.dto.SegmentPreviewView;
import me.nageshk.omnichannel.crm.segment.dto.SegmentRequest;
import me.nageshk.omnichannel.crm.segment.dto.SegmentView;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Segments (spec D §5): crm:view here, crm:segments in the service, so the two refusals read the same (403). */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/crm/segments")
public class CrmSegmentController {

    private static final String VIEW = "@access.has(#workspaceId, 'crm:view')";

    private final CrmAccess crmAccess;
    private final SegmentService segments;

    CrmSegmentController(CrmAccess crmAccess, SegmentService segments) {
        this.crmAccess = crmAccess;
        this.segments = segments;
    }

    /** The preview body: a rule tree. */
    public record PreviewRequest(SegmentRuleGroup rules) {}

    @GetMapping
    @PreAuthorize(VIEW)
    public List<SegmentView> list(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId) {
        return segments.list(crmAccess.resolve(me, workspaceId));
    }

    @GetMapping("/fields")
    @PreAuthorize(VIEW)
    public SegmentCatalogView fields(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId) {
        return segments.fields(crmAccess.resolve(me, workspaceId));
    }

    @PostMapping("/preview")
    @PreAuthorize(VIEW)
    public SegmentPreviewView preview(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @RequestBody PreviewRequest req) {
        return segments.preview(crmAccess.resolve(me, workspaceId), req.rules());
    }

    @GetMapping("/{id}")
    @PreAuthorize(VIEW)
    public SegmentView get(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id) {
        return segments.get(crmAccess.resolve(me, workspaceId), id);
    }

    @PostMapping("/{id}/preview")
    @PreAuthorize(VIEW)
    public SegmentPreviewView previewSaved(@AuthenticationPrincipal AuthenticatedUser me,
            @PathVariable String workspaceId, @PathVariable String id) {
        return segments.preview(crmAccess.resolve(me, workspaceId), id);
    }

    @PostMapping
    @PreAuthorize(VIEW)
    @ResponseStatus(HttpStatus.CREATED)
    public SegmentView create(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @RequestBody SegmentRequest req) {
        return segments.create(crmAccess.resolve(me, workspaceId), req);
    }

    @PutMapping("/{id}")
    @PreAuthorize(VIEW)
    public SegmentView update(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id, @RequestBody SegmentRequest req) {
        return segments.update(crmAccess.resolve(me, workspaceId), id, req);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(VIEW)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @PathVariable String id) {
        segments.delete(crmAccess.resolve(me, workspaceId), id);
    }
}
