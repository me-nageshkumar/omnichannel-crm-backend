package me.nageshk.omnichannel.campaign;

import java.time.Clock;
import java.util.List;
import me.nageshk.omnichannel.contact.FilterKind;
import org.springframework.stereotype.Component;

/** Builds the views of campaigns. */
@Component
public class CampaignViews {

    private final CampaignSenderService senderService;
    private final CampaignSettingsService settingsService;
    private final Clock clock;

    CampaignViews(CampaignSenderService senderService, CampaignSettingsService settingsService, Clock clock) {
        this.senderService = senderService;
        this.settingsService = settingsService;
        this.clock = clock;
    }

    /** The campaign with its estimate and senders. */
    CampaignView detail(Campaign c) {
        List<SenderView> senders = senderService.views(c.getWorkspaceId(), c.getSenderIds());
        int perDay = senders.stream().filter(s -> s.state() == SenderState.WARMING || s.state() == SenderState.ACTIVE)
                .mapToInt(SenderView::capToday).sum();
        int remaining = c.getCounts() == null ? 0 : c.getCounts().pending();
        Estimate estimate = CampaignEstimates.of(remaining, perDay, settingsService.get(c.getWorkspaceId()),
                clock.instant());
        return build(c, estimate, senders);
    }

    /** A list item: no estimate, no senders. */
    CampaignView summary(Campaign c) {
        return build(c, null, null);
    }

    private static CampaignView build(Campaign c, Estimate estimate, List<SenderView> senders) {
        Campaign.Audience a = c.getAudience();
        CampaignView.AudienceView audience = a == null ? null : new CampaignView.AudienceView(a.kind(),
                a.filterId(), a.filterName(), a.filterSpec(), a.csvFileName(), a.csvHeaders(), a.csvRows(),
                a.phoneColumn(), a.nameColumn(),
                a.kind() == AudienceKind.FILTER ? FilterKind.orSimple(a.filterKind()) : null);
        return new CampaignView(c.getId(), c.getName(), c.getStatus(), c.getPausedBy(), c.getPauseReason(), audience,
                c.getTemplateIds(), c.getTemplateNames(), c.getPlaceholders(), c.getSenderIds(), c.getCounts(), estimate,
                senders, c.getCreatedBy(), c.getCreationTime(), c.getStartedAt(), c.getFinishedAt(),
                c.getLastUpdateTime(), c.getVersion());
    }
}
