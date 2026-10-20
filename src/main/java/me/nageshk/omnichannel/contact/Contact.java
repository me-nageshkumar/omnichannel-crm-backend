package me.nageshk.omnichannel.contact;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/** A person a brand talks to; unique per brand by phone and by WhatsApp chat id. */
@Document("contacts")
@CompoundIndexes({
        @CompoundIndex(name = "ws_phone", def = "{'workspaceId': 1, 'phone': 1}", unique = true,
                partialFilter = "{'phone': {$type: 'string'}}"),
        @CompoundIndex(name = "ws_waId", def = "{'workspaceId': 1, 'waId': 1}", unique = true,
                partialFilter = "{'waId': {$type: 'string'}}"),
        @CompoundIndex(name = "ws_lastMessageAt", def = "{'workspaceId': 1, 'lastMessageAt': -1}"),
        @CompoundIndex(name = "ws_labels", def = "{'workspaceId': 1, 'labels': 1}")
})
public class Contact {

    @Id
    private String id;
    private String workspaceId;
    private String customerId;
    private String waId;
    private String phone;
    private String name;
    private boolean nameEdited;
    private Set<String> labels = new HashSet<>();
    private Map<String, String> attributes = new HashMap<>();
    private ContactSource source;
    private Instant lastMessageAt;
    @CreatedDate
    private Instant creationTime;
    @LastModifiedDate
    private Instant lastUpdateTime;
    @Version
    private Long version;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    public String getWaId() { return waId; }
    public void setWaId(String waId) { this.waId = waId; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public boolean isNameEdited() { return nameEdited; }
    public void setNameEdited(boolean nameEdited) { this.nameEdited = nameEdited; }
    public Set<String> getLabels() { return labels; }
    public void setLabels(Set<String> labels) { this.labels = labels; }
    public Map<String, String> getAttributes() { return attributes; }
    public void setAttributes(Map<String, String> attributes) { this.attributes = attributes; }
    public ContactSource getSource() { return source; }
    public void setSource(ContactSource source) { this.source = source; }
    public Instant getLastMessageAt() { return lastMessageAt; }
    public void setLastMessageAt(Instant lastMessageAt) { this.lastMessageAt = lastMessageAt; }
    public Instant getCreationTime() { return creationTime; }
    public void setCreationTime(Instant creationTime) { this.creationTime = creationTime; }
    public Instant getLastUpdateTime() { return lastUpdateTime; }
    public void setLastUpdateTime(Instant lastUpdateTime) { this.lastUpdateTime = lastUpdateTime; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
