package me.nageshk.omnichannel.ai.knowledge;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** A brand's named set of sources, held as one store at the provider of its connection. */
@Document("aiKnowledgeBases")
@CompoundIndex(name = "workspace_name_unique", def = "{'workspaceId': 1, 'name': 1}", unique = true)
public class KnowledgeBase {

    @Id
    private String id;
    private String workspaceId;
    private String customerId;
    private String name;
    private String description;
    private String connectionId;
    private String provider;
    private String providerStoreId;
    private KnowledgeBaseStatus status = KnowledgeBaseStatus.READY;
    private String error;
    private Instant createdAt;
    private String createdBy;
    private Instant updatedAt;
    @Version
    private Long version;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getConnectionId() { return connectionId; }
    public void setConnectionId(String connectionId) { this.connectionId = connectionId; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getProviderStoreId() { return providerStoreId; }
    public void setProviderStoreId(String providerStoreId) { this.providerStoreId = providerStoreId; }
    public KnowledgeBaseStatus getStatus() { return status; }
    public void setStatus(KnowledgeBaseStatus status) { this.status = status; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
