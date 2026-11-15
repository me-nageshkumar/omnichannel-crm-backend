package me.nageshk.omnichannel.ai.profile;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** A brand's reusable AI setup: a connection, a model, instructions, tools. {@code connectionId} is a plain string. */
@Document("aiProfiles")
@CompoundIndex(name = "workspace_name_unique", def = "{'workspaceId': 1, 'name': 1}", unique = true)
public class AiProfile {

    @Id
    private String id;
    private String workspaceId;
    private String customerId;
    private String name;
    private String connectionId;
    private String model;
    private String instructions;
    private String reasoningEffort;
    private Integer maxOutputTokens;
    private Double temperature;
    private boolean webSearch;
    private List<String> vectorStoreIds = new ArrayList<>();
    private List<McpServer> mcpServers = new ArrayList<>();
    private Instant updatedAt;
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
    public String getConnectionId() { return connectionId; }
    public void setConnectionId(String connectionId) { this.connectionId = connectionId; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getInstructions() { return instructions; }
    public void setInstructions(String instructions) { this.instructions = instructions; }
    public String getReasoningEffort() { return reasoningEffort; }
    public void setReasoningEffort(String reasoningEffort) { this.reasoningEffort = reasoningEffort; }
    public Integer getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(Integer maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public boolean isWebSearch() { return webSearch; }
    public void setWebSearch(boolean webSearch) { this.webSearch = webSearch; }
    public List<String> getVectorStoreIds() { return vectorStoreIds; }
    public void setVectorStoreIds(List<String> vectorStoreIds) { this.vectorStoreIds = vectorStoreIds; }
    public List<McpServer> getMcpServers() { return mcpServers; }
    public void setMcpServers(List<McpServer> mcpServers) { this.mcpServers = mcpServers; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
