package me.nageshk.omnichannel.service.campaign;

import java.util.List;
import me.nageshk.omnichannel.db.campaign.AudienceKind;
import me.nageshk.omnichannel.db.campaign.Campaign;

/** Body of create and update. */
public record CampaignRequest(String name, AudienceRequest audience, List<String> templateIds,
        List<Campaign.PlaceholderMapping> placeholders, List<String> senderIds) {

    public record AudienceRequest(AudienceKind kind, String filterId, String phoneColumn, String nameColumn) {
    }
}
