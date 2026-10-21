package me.nageshk.omnichannel.campaign;

import java.util.List;

/** Body of create and update. */
public record CampaignRequest(String name, AudienceRequest audience, List<String> templateIds,
        List<Campaign.PlaceholderMapping> placeholders, List<String> senderIds) {

    public record AudienceRequest(AudienceKind kind, String filterId, String phoneColumn, String nameColumn) {
    }
}
