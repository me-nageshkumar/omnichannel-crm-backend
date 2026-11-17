package me.nageshk.omnichannel.ai.connection;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import me.nageshk.omnichannel.common.secret.SealedSecret;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** A brand's account at an AI provider; the key is sealed and never leaves the server. */
@Document("aiConnections")
@CompoundIndex(name = "workspace_name_unique", def = "{'workspaceId': 1, 'name': 1}", unique = true)
public class AiConnection {

    @Id
    private String id;
    private String workspaceId;
    private String customerId;
    private String name;
    private String provider;
    private SealedSecret apiKey;
    /** The provider's plain settings (never secrets); absent on documents saved before they existed. */
    private Map<String, String> settings = new LinkedHashMap<>();
    private String keyHint;
    private Instant keySetAt;
    private AiConnectionStatus status = AiConnectionStatus.UNVERIFIED;
    private Instant checkedAt;
    private String lastError;
    private Instant createdAt;
    private String updatedBy;
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
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public SealedSecret getApiKey() { return apiKey; }
    public void setApiKey(SealedSecret apiKey) { this.apiKey = apiKey; }
    public Map<String, String> getSettings() { return settings == null ? new LinkedHashMap<>() : settings; }
    public void setSettings(Map<String, String> settings) { this.settings = settings; }
    public String getKeyHint() { return keyHint; }
    public void setKeyHint(String keyHint) { this.keyHint = keyHint; }
    public Instant getKeySetAt() { return keySetAt; }
    public void setKeySetAt(Instant keySetAt) { this.keySetAt = keySetAt; }
    public AiConnectionStatus getStatus() { return status; }
    public void setStatus(AiConnectionStatus status) { this.status = status; }
    public Instant getCheckedAt() { return checkedAt; }
    public void setCheckedAt(Instant checkedAt) { this.checkedAt = checkedAt; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
