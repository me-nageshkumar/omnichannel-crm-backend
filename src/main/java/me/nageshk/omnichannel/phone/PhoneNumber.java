package me.nageshk.omnichannel.phone;

import java.time.Instant;
import me.nageshk.omnichannel.enablement.Disabled;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** A brand's WhatsApp number, and the one device key that may connect WhatsApp Web for it. */
@Document("phone_numbers")
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

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getNumber() { return number; }
    public void setNumber(String number) { this.number = number; }
    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    /** WhatsApp Web for numbers saved before the field existed. */
    public ConnectionType getConnectionType() { return connectionType == null ? ConnectionType.WHATSAPP_WEB : connectionType; }
    public void setConnectionType(ConnectionType connectionType) { this.connectionType = connectionType; }
    public Disabled getDisabled() { return disabled; }
    public void setDisabled(Disabled disabled) { this.disabled = disabled; }
    public String getDeviceKeyHash() { return deviceKeyHash; }
    public void setDeviceKeyHash(String deviceKeyHash) { this.deviceKeyHash = deviceKeyHash; }
    public String getDeviceKeyId() { return deviceKeyId; }
    public void setDeviceKeyId(String deviceKeyId) { this.deviceKeyId = deviceKeyId; }
    public Instant getDeviceKeyIssuedAt() { return deviceKeyIssuedAt; }
    public void setDeviceKeyIssuedAt(Instant deviceKeyIssuedAt) { this.deviceKeyIssuedAt = deviceKeyIssuedAt; }
    public Instant getLastConnectedAt() { return lastConnectedAt; }
    public void setLastConnectedAt(Instant lastConnectedAt) { this.lastConnectedAt = lastConnectedAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public String getLastWrongNumber() { return lastWrongNumber; }
    public void setLastWrongNumber(String lastWrongNumber) { this.lastWrongNumber = lastWrongNumber; }
    public Instant getLastWrongNumberAt() { return lastWrongNumberAt; }
    public void setLastWrongNumberAt(Instant lastWrongNumberAt) { this.lastWrongNumberAt = lastWrongNumberAt; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreationTime() { return creationTime; }
    public void setCreationTime(Instant creationTime) { this.creationTime = creationTime; }
    public Instant getLastUpdateTime() { return lastUpdateTime; }
    public void setLastUpdateTime(Instant lastUpdateTime) { this.lastUpdateTime = lastUpdateTime; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
