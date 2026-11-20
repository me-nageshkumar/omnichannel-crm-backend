package me.nageshk.omnichannel.service.campaign;

import java.time.Instant;
import java.util.List;
import me.nageshk.omnichannel.db.campaign.AudienceKind;
import me.nageshk.omnichannel.db.campaign.Campaign;
import me.nageshk.omnichannel.db.campaign.CampaignStatus;
import me.nageshk.omnichannel.db.campaign.PausedBy;
import me.nageshk.omnichannel.db.contact.ContactFilterSpec;
import me.nageshk.omnichannel.db.contact.FilterKind;
import me.nageshk.omnichannel.db.phone.ConnectionType;

/** A campaign as the pages show it. */
public record CampaignView(String id, String name, CampaignStatus status, PausedBy pausedBy, String pauseReason,
        AudienceView audience, List<String> templateIds, List<String> templateNames,
        List<Campaign.PlaceholderMapping> placeholders, List<String> senderIds, Campaign.Counts counts,
        Estimate estimate, List<SenderView> senders, String createdBy, Instant creationTime, Instant startedAt,
        Instant finishedAt, Instant lastUpdateTime, Long version, ConnectionType channel,
        CloudTemplateView cloudTemplate, List<CloudSenderView> cloudSenders) {

    /** The Meta template of a Cloud campaign, with its status at Meta now ({@code DELETED} when gone). */
    public record CloudTemplateView(String cloudTemplateId, String name, String language, String status,
            String headerMediaId) {
    }

    /** The audience without the stored file's id. */
    public record AudienceView(AudienceKind kind, String filterId, String filterName, ContactFilterSpec filterSpec,
            String csvFileName, List<String> csvHeaders, Integer csvRows, String phoneColumn, String nameColumn,
            FilterKind filterKind, String segmentId, String segmentName) {
    }
}
