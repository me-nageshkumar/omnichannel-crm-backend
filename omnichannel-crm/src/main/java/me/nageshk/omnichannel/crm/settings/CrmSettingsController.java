package me.nageshk.omnichannel.crm.settings;

import me.nageshk.omnichannel.crm.access.CrmAccess;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.pipeline.CrmPipelineService;
import me.nageshk.omnichannel.crm.settings.dto.SettingsRequest;
import me.nageshk.omnichannel.crm.settings.dto.SettingsView;
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
@RequestMapping("/api/workspaces/{workspaceId}/crm/settings")
public class CrmSettingsController {

    private final CrmAccess crmAccess;
    private final CrmSettingsService service;
    private final CrmPipelineService pipelines;

    CrmSettingsController(CrmAccess crmAccess, CrmSettingsService service, CrmPipelineService pipelines) {
        this.crmAccess = crmAccess;
        this.service = service;
        this.pipelines = pipelines;
    }

    @GetMapping
    @PreAuthorize("@access.has(#workspaceId, 'crm:view')")
    public SettingsView get(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId) {
        return SettingsView.of(crmAccess.resolve(me, workspaceId).settings());
    }

    @PutMapping
    @PreAuthorize("@access.has(#workspaceId, 'crm:view') and @access.has(#workspaceId, 'crm:settings')")
    public SettingsView put(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String workspaceId,
            @RequestBody SettingsRequest request) {
        CrmContext ctx = crmAccess.resolve(me, workspaceId);
        ctx.require("crm:settings");
        CrmSettings saved = service.update(ctx, request);
        String wanted = request.defaultPipelineId();
        // the default pipeline is changed only through the pipelines (one flag, one setting); null = unchanged
        if (wanted != null && !wanted.equals(saved.getDefaultPipelineId())) {
            pipelines.makeDefault(ctx, wanted);
            saved = service.find(workspaceId).orElse(saved);
        }
        return SettingsView.of(saved);
    }
}
