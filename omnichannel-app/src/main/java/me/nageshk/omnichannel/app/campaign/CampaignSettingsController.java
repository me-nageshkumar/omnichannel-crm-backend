package me.nageshk.omnichannel.app.campaign;

import me.nageshk.omnichannel.service.campaign.CampaignSettingsService;
import me.nageshk.omnichannel.service.campaign.CampaignSettingsView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code campaign:settings} reads and edits the workspace's campaign settings. */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/campaigns/settings")
class CampaignSettingsController {

    private final CampaignSettingsService service;

    CampaignSettingsController(CampaignSettingsService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("@access.has(#workspaceId, 'campaign:settings')")
    CampaignSettingsView get(@PathVariable String workspaceId) {
        return CampaignSettingsView.of(service.get(workspaceId));
    }

    @PutMapping
    @PreAuthorize("@access.has(#workspaceId, 'campaign:settings')")
    CampaignSettingsView put(@PathVariable String workspaceId, @RequestBody CampaignSettingsView body) {
        return CampaignSettingsView.of(service.update(workspaceId, body));
    }
}
