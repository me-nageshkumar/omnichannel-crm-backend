package me.nageshk.omnichannel.campaign;

import java.util.List;
import org.springframework.stereotype.Component;

/** Builds the views of campaigns. */
@Component
public class CampaignViews {

    CampaignView detail(Campaign c) {
        Campaign.Audience a = c.getAudience();
        CampaignView.AudienceView audience = a == null ? null : new CampaignView.AudienceView(a.kind(),
                a.filterId(), a.filterName(), a.filterSpec(), a.csvFileName(), a.csvHeaders(), a.csvRows(),
                a.phoneColumn(), a.nameColumn());
        return new CampaignView(c.getId(), c.getName(), c.getStatus(), c.getPausedBy(), c.getPauseReason(), audience,
                c.getTemplateIds(), c.getTemplateNames(), c.getPlaceholders(), c.getSenderIds(), c.getCounts(), null,
                List.of(), c.getCreatedBy(), c.getCreationTime(), c.getStartedAt(), c.getFinishedAt(),
                c.getLastUpdateTime(), c.getVersion());
    }
}
