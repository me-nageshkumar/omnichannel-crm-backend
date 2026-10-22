package me.nageshk.omnichannel.campaign;

import java.time.Instant;
import java.util.List;
import me.nageshk.omnichannel.contact.ContactFilterSpec;
import me.nageshk.omnichannel.contact.FilterKind;

/** A campaign as the pages show it. */
public record CampaignView(String id, String name, CampaignStatus status, PausedBy pausedBy, String pauseReason,
        AudienceView audience, List<String> templateIds, List<String> templateNames,
        List<Campaign.PlaceholderMapping> placeholders, List<String> senderIds, Campaign.Counts counts,
        Estimate estimate, List<SenderView> senders, String createdBy, Instant creationTime, Instant startedAt,
        Instant finishedAt, Instant lastUpdateTime, Long version) {

    /** The audience without the stored file's id. */
    public record AudienceView(AudienceKind kind, String filterId, String filterName, ContactFilterSpec filterSpec,
            String csvFileName, List<String> csvHeaders, Integer csvRows, String phoneColumn, String nameColumn,
            FilterKind filterKind) {
    }
}
