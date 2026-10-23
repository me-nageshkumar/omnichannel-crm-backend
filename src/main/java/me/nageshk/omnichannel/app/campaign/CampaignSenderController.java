package me.nageshk.omnichannel.app.campaign;

import java.util.List;
import me.nageshk.omnichannel.service.campaign.CampaignSenderService;
import me.nageshk.omnichannel.service.campaign.SenderUpdate;
import me.nageshk.omnichannel.service.campaign.SenderView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/campaigns/senders")
class CampaignSenderController {

    private final CampaignSenderService service;

    CampaignSenderController(CampaignSenderService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("@access.has(#workspaceId, 'campaign:view')")
    List<SenderView> list(@PathVariable String workspaceId) {
        return service.list(workspaceId);
    }

    @PutMapping("/{phoneNumberId}")
    @PreAuthorize("@access.has(#workspaceId, 'campaign:settings')")
    SenderView update(@PathVariable String workspaceId, @PathVariable String phoneNumberId,
            @RequestBody SenderUpdate body) {
        return service.update(workspaceId, phoneNumberId, body);
    }
}
