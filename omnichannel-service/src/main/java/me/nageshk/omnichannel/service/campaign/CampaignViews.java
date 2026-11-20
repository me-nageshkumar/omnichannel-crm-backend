package me.nageshk.omnichannel.service.campaign;

import java.time.Clock;
import java.util.List;
import me.nageshk.omnichannel.db.campaign.AudienceKind;
import me.nageshk.omnichannel.db.campaign.Campaign;
import me.nageshk.omnichannel.db.campaign.SenderState;
import me.nageshk.omnichannel.db.contact.FilterKind;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.db.campaign.CampaignCloudTemplate;
import me.nageshk.omnichannel.meta.api.CloudTemplates;
import org.springframework.stereotype.Component;

/** Builds the views of campaigns. */
@Component
public class CampaignViews {

    private final CampaignSenderService senderService;
    private final CampaignSettingsService settingsService;
    private final Clock clock;
    private final CloudCampaignSenders cloudSenders;
    private final CloudTemplates cloudTemplates;

    CampaignViews(CampaignSenderService senderService, CampaignSettingsService settingsService, Clock clock,
            CloudCampaignSenders cloudSenders, CloudTemplates cloudTemplates) {
        this.cloudSenders = cloudSenders;
        this.cloudTemplates = cloudTemplates;
        this.senderService = senderService;
        this.settingsService = settingsService;
        this.clock = clock;
    }

    /** The campaign with its estimate and senders. */
    public CampaignView detail(Campaign c) {
        if (c.isCloud()) return cloudDetail(c);
        List<SenderView> senders = senderService.views(c.getWorkspaceId(), c.getSenderIds());
        int perDay = senders.stream().filter(s -> s.state() == SenderState.WARMING || s.state() == SenderState.ACTIVE)
                .mapToInt(SenderView::capToday).sum();
        int remaining = c.getCounts() == null ? 0 : c.getCounts().pending();
        Estimate estimate = CampaignEstimates.of(remaining, perDay, settingsService.get(c.getWorkspaceId()),
                clock.instant());
        return build(c, estimate, senders, null);
    }

    /** A Cloud campaign goes on at its WhatsApp account's daily limit (spec 2026-11-21 §5.7). */
    private CampaignView cloudDetail(Campaign c) {
        List<CloudSenderView> mine = cloudSenders.list(c.getWorkspaceId()).stream()
                .filter(v -> c.getSenderIds().contains(v.phoneNumberId())).toList();
        int remaining = c.getCounts() == null ? 0 : c.getCounts().pending();
        String waba = mine.stream().map(CloudSenderView::wabaId).filter(java.util.Objects::nonNull).findFirst()
                .orElse(null);
        Integer limit = waba == null ? CloudLimits.DEFAULT_LIMIT : cloudSenders.dailyLimit(c.getWorkspaceId(), waba);
        int perDay = limit == null ? Math.max(remaining, 1) : limit;
        Estimate estimate = CampaignEstimates.of(remaining, perDay, settingsService.get(c.getWorkspaceId()),
                clock.instant());
        return build(c, estimate, List.of(), mine);
    }

    /** A list item: no estimate, no senders. */
    CampaignView summary(Campaign c) {
        return build(c, null, null, null);
    }

    private CampaignView build(Campaign c, Estimate estimate, List<SenderView> senders,
            List<CloudSenderView> cloud) {
        Campaign.Audience a = c.getAudience();
        CampaignView.AudienceView audience = a == null ? null : new CampaignView.AudienceView(a.kind(),
                a.filterId(), a.filterName(), a.filterSpec(), a.csvFileName(), a.csvHeaders(), a.csvRows(),
                a.phoneColumn(), a.nameColumn(),
                a.kind() == AudienceKind.FILTER ? FilterKind.orSimple(a.filterKind()) : null, a.segmentId(),
                a.segmentName());
        return new CampaignView(c.getId(), c.getName(), c.getStatus(), c.getPausedBy(), c.getPauseReason(), audience,
                c.getTemplateIds(), c.getTemplateNames(), c.getPlaceholders(), c.getSenderIds(), c.getCounts(), estimate,
                senders, c.getCreatedBy(), c.getCreationTime(), c.getStartedAt(), c.getFinishedAt(),
                c.getLastUpdateTime(), c.getVersion(), c.getChannel(), cloudTemplateOf(c), cloud);
    }

    private CampaignView.CloudTemplateView cloudTemplateOf(Campaign c) {
        CampaignCloudTemplate t = c.getCloudTemplate();
        if (t == null) return null;
        String status;
        try {
            status = cloudTemplates.get(c.getWorkspaceId(), t.cloudTemplateId()).status();
        } catch (NotFoundException gone) {
            status = "DELETED";
        }
        return new CampaignView.CloudTemplateView(t.cloudTemplateId(), t.name(), t.language(), status,
                t.headerMediaId());
    }
}
