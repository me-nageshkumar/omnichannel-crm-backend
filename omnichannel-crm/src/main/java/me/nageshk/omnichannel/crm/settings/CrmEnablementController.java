package me.nageshk.omnichannel.crm.settings;

import me.nageshk.omnichannel.crm.settings.dto.EnablementRequest;
import me.nageshk.omnichannel.crm.settings.dto.EnablementView;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The platform's per-brand CRM switch. */
@RestController
@RequestMapping("/api/customers/{customerId}/workspaces/{workspaceId}/crm")
public class CrmEnablementController {

    private final CrmSettingsService service;

    CrmEnablementController(CrmSettingsService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('crm:enable')")
    public EnablementView get(@PathVariable String customerId, @PathVariable String workspaceId) {
        service.requireBrand(customerId, workspaceId);
        return service.find(workspaceId).map(CrmEnablementController::view)
                .orElseGet(() -> new EnablementView(false, null, null));
    }

    @PutMapping
    @PreAuthorize("hasAuthority('crm:enable')")
    public EnablementView put(@AuthenticationPrincipal AuthenticatedUser me, @PathVariable String customerId,
            @PathVariable String workspaceId, @RequestBody EnablementRequest request) {
        return view(service.setSwitch(me, customerId, workspaceId, request));
    }

    private static EnablementView view(CrmSettings s) {
        return new EnablementView(s.isEnabled(), s.getMaxPipelines(), s.getMaxCustomFields());
    }
}
