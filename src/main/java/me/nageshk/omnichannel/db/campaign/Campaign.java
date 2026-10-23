package me.nageshk.omnichannel.db.campaign;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import me.nageshk.omnichannel.db.contact.ContactFilterSpec;
import me.nageshk.omnichannel.db.contact.FilterKind;
import me.nageshk.omnichannel.db.contact.advanced.Uses;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/** A bulk send of text templates to an audience, through chosen senders. The name is unique per brand ignoring case. */
@Document("campaigns")
@CompoundIndexes({
        @CompoundIndex(name = "ws_nameKey", def = "{'workspaceId': 1, 'nameKey': 1}", unique = true),
        @CompoundIndex(name = "ws_status", def = "{'workspaceId': 1, 'status': 1}")
})
@Getter
@Setter
public class Campaign {

    @Id
    private String id;
    private String workspaceId;
    private String customerId;
    private String name;
    private String nameKey;
    private Audience audience;
    private List<String> templateIds;
    private List<String> templateNames;
    private List<PlaceholderMapping> placeholders;
    private List<String> senderIds;
    private CampaignStatus status;
    private PausedBy pausedBy;
    private String pauseReason;
    private Counts counts;
    private String createdBy;
    private Instant creationTime;
    private Instant startedAt;
    private Instant finishedAt;
    @LastModifiedDate
    private Instant lastUpdateTime;
    @Version
    private Long version;

    /** The audience as it was when the campaign was saved: a filter's name and spec are snapshotted, and for an advanced one its stored query and uses. */
    public record Audience(AudienceKind kind, String filterId, String filterName, ContactFilterSpec filterSpec,
            String csvFileName, List<String> csvHeaders, Integer csvRows, String phoneColumn, String nameColumn,
            String csvFileId, FilterKind filterKind, String filterQueryJson, Uses uses) {
    }

    public record PlaceholderMapping(String name, PlaceholderSource source, String fallback) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PlaceholderSource(PlaceholderSourceKind kind, String field, String attributeId, String column,
            String text) {
    }

    public record Counts(int total, int pending, int sent, int delivered, int read, int replied, int skipped,
            int failed) {
        public static final Counts ZERO = new Counts(0, 0, 0, 0, 0, 0, 0, 0);
    }
}
