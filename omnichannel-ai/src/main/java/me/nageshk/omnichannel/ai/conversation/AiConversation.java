package me.nageshk.omnichannel.ai.conversation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.ai.api.TurnRequest;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One AI conversation of a chatbot run's node: the provider's opaque state (OpenAI: the last response id), the
 * neutral transcript (last {@link #MAX_TRANSCRIPT} items, always kept so an expired state or a TRANSCRIPT provider can
 * rebuild) and the finish calls not yet answered. Kept, no TTL.
 */
@Document("aiConversations")
@CompoundIndex(name = "owner", def = "{'ownerType': 1, 'ownerId': 1, 'nodeName': 1}")
public class AiConversation {

    public static final String CHATBOT_RUN = "CHATBOT_RUN";
    public static final int MAX_TRANSCRIPT = 100;

    @Id
    private String id;
    private String workspaceId;
    private String ownerType;
    private String ownerId;
    private String nodeName;
    private String provider;
    private String profileId;
    private Map<String, Object> state = new LinkedHashMap<>();
    private List<Map<String, Object>> transcript = new ArrayList<>();
    /** How many of the oldest items the cap has dropped, so a call's transcript position stays meaningful. */
    private int dropped;
    private List<String> openCallIds = new ArrayList<>();
    /** The last turn that moved the conversation on; only it may be superseded. */
    private String lastCallId;
    private int turns;
    private Instant createdAt;
    private Instant updatedAt;
    @Version
    private Long version;

    /** A new conversation owned by the request's chatbot run and node. */
    public static AiConversation start(TurnRequest r, String provider, Instant now) {
        AiConversation c = new AiConversation();
        c.setWorkspaceId(r.workspaceId());
        c.setOwnerType(CHATBOT_RUN);
        c.setOwnerId(r.runId());
        c.setNodeName(r.nodeName());
        c.setProvider(provider);
        c.setProfileId(r.profileId());
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return c;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getOwnerType() { return ownerType; }
    public void setOwnerType(String ownerType) { this.ownerType = ownerType; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public String getNodeName() { return nodeName; }
    public void setNodeName(String nodeName) { this.nodeName = nodeName; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getProfileId() { return profileId; }
    public void setProfileId(String profileId) { this.profileId = profileId; }
    public Map<String, Object> getState() { return state; }
    public void setState(Map<String, Object> state) { this.state = state; }
    public List<Map<String, Object>> getTranscript() { return transcript; }
    public void setTranscript(List<Map<String, Object>> transcript) { this.transcript = transcript; }
    public int getDropped() { return dropped; }
    public void setDropped(int dropped) { this.dropped = dropped; }
    public List<String> getOpenCallIds() { return openCallIds; }
    public void setOpenCallIds(List<String> openCallIds) { this.openCallIds = openCallIds; }
    public String getLastCallId() { return lastCallId; }
    public void setLastCallId(String lastCallId) { this.lastCallId = lastCallId; }
    public int getTurns() { return turns; }
    public void setTurns(int turns) { this.turns = turns; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
