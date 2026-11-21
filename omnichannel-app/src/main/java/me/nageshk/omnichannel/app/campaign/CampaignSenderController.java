package me.nageshk.omnichannel.app.campaign;

import java.util.List;
import me.nageshk.omnichannel.db.phone.ConnectionType;
import me.nageshk.omnichannel.service.campaign.CampaignSenderService;
import me.nageshk.omnichannel.service.campaign.CloudCampaignSenders;
import me.nageshk.omnichannel.service.campaign.SenderUpdate;
import me.nageshk.omnichannel.service.campaign.SenderView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/campaigns/senders")
class CampaignSenderController {

    private final CampaignSenderService service;
    private final CloudCampaignSenders cloudSenders;

    CampaignSenderController(CampaignSenderService service, CloudCampaignSenders cloudSenders) {
        this.service = service;
        this.cloudSenders = cloudSenders;
    }

    /** The WhatsApp Web senders (unchanged), or with {@code channel=WHATSAPP_CLOUD_API} the Cloud numbers. */
    @GetMapping
    @PreAuthorize("@access.has(#workspaceId, 'campaign:view')")
    List<?> list(@PathVariable String workspaceId,
            @RequestParam(defaultValue = "WHATSAPP_WEB") ConnectionType channel) {
        return channel == ConnectionType.WHATSAPP_CLOUD_API ? cloudSenders.list(workspaceId)
                : service.list(workspaceId);
    }

    @PutMapping("/{phoneNumberId}")
    @PreAuthorize("@access.has(#workspaceId, 'campaign:settings')")
    SenderView update(@PathVariable String workspaceId, @PathVariable String phoneNumberId,
            @RequestBody SenderUpdate body) {
        return service.update(workspaceId, phoneNumberId, body);
    }
}
