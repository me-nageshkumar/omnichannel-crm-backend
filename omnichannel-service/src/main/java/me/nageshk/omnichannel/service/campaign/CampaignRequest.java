package me.nageshk.omnichannel.service.campaign;

import java.util.List;
import me.nageshk.omnichannel.db.campaign.AudienceKind;
import me.nageshk.omnichannel.db.campaign.Campaign;
import me.nageshk.omnichannel.db.phone.ConnectionType;

/** Body of create and update. */
public record CampaignRequest(String name, AudienceRequest audience, List<String> templateIds,
        List<Campaign.PlaceholderMapping> placeholders, List<String> senderIds, ConnectionType channel,
        CloudTemplateChoice cloudTemplate) {

    /** A WhatsApp Web request, as before Cloud campaigns; {@code channel} absent means WhatsApp Web. */
    public CampaignRequest(String name, AudienceRequest audience, List<String> templateIds,
            List<Campaign.PlaceholderMapping> placeholders, List<String> senderIds) {
        this(name, audience, templateIds, placeholders, senderIds, null, null);
    }

    /** The Meta template of a Cloud campaign and, for a media header, the app's uploaded file. */
    public record CloudTemplateChoice(String cloudTemplateId, String headerMediaId) {
    }

    public record AudienceRequest(AudienceKind kind, String filterId, String phoneColumn, String nameColumn,
            String segmentId) {
    }
}
