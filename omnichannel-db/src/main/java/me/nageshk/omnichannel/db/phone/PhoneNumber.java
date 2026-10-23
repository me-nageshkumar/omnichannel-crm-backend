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

    @Indexed(unique = true)
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
