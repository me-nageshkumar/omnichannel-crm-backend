package me.nageshk.omnichannel.db.phone;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import me.nageshk.omnichannel.db.enablement.Disabled;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** A brand's WhatsApp number, and the one device key that may connect WhatsApp Web for it. */
@Document("phoneNumbers")
@Getter
@Setter
public class PhoneNumber {

    @Id
    private String id;

    /**
     * Null on an archive. Uniqueness is the partial index {@code number_unique} owned by
     * {@link PhoneNumberIndexMigration}, not an annotation, so archives (no number) may coexist.
     */
    private String number;
    @Indexed
    private String customerId;
    @Indexed
    private String workspaceId;
    private String label;
    /** The team that works this number (chat-assignment design §3.1); null until an admin sets one. */
    @Indexed(sparse = true)
    private String teamId;
    private ConnectionType connectionType;
    private Disabled disabled;

    /**
     * Set on the archive a move leaves behind: the digits of the number that moved away. The archive keeps the
     * old brand's chats; it has no {@code number}, never connects and does not count toward the number limit.
     */
    @Indexed(sparse = true)
    private String archiveOf;
    private Instant archivedAt;
    /** Set while the number is being moved to another brand; its device is refused until the move finishes. */
    private Instant movingSince;
    /** When the number last arrived in its current brand by a move. */
    private Instant movedInAt;

    public boolean isArchive() { return archiveOf != null; }

    @Indexed(sparse = true)
    private String deviceKeyHash;
    private String deviceKeyId;
    private Instant deviceKeyIssuedAt;

    private Instant lastConnectedAt;
    private Instant lastSeenAt;
    private String lastWrongNumber;
    private Instant lastWrongNumberAt;

    private String createdBy;
    @CreatedDate
    private Instant creationTime;
    @LastModifiedDate
    private Instant lastUpdateTime;
    @Version
    private Long version;

    /** WhatsApp Web for numbers saved before the field existed. */
    public ConnectionType getConnectionType() { return connectionType == null ? ConnectionType.WHATSAPP_WEB : connectionType; }
}
