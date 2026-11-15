package me.nageshk.omnichannel.ai.call;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.ai.api.AiFinish;
import me.nageshk.omnichannel.ai.api.AiReplyFormat;
import me.nageshk.omnichannel.ai.api.AiUsage;
import me.nageshk.omnichannel.ai.api.TurnRequest;
import me.nageshk.omnichannel.ai.api.TurnResult;
import me.nageshk.omnichannel.ai.api.TurnStatus;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * One AI turn: the queue item (PENDING → RUNNING under a lease → DONE | FAILED) and, once finished, its history.
 * Holds the request as submitted and never a credential: keys are opened when the turn is claimed. Kept, no TTL.
 */
@Document("aiCalls")
@CompoundIndexes({
        @CompoundIndex(name = "status_lease", def = "{'status': 1, 'leaseUntil': 1}"),
        @CompoundIndex(name = "workspace_created", def = "{'workspaceId': 1, 'createdAt': -1}")})
public class AiCall {

    /** One app-side tool call of the turn; the result is cut to an excerpt. */
    public record ToolCallLog(String name, long ms, boolean ok, String resultExcerpt) {}

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Id
    private String id;
    private String workspaceId;
    private String conversationId;
    @Indexed
    private String runId;
    private String chatbotId;
    private String nodeName;
    private String profileId;
    private String provider;
    private String model;
    /** The {@link TurnRequest} as submitted. */
    private Map<String, Object> request;
    private AiCallStatus status;
    private int attempts;
    private Instant leaseUntil;
    private Instant deliveredAt;
    private String input;
    private String reply;
    private AiFinish finish;
    private List<ToolCallLog> toolCalls = new ArrayList<>();
    private AiUsage usage;
    private boolean toolsUsed;
    private Long latencyMs;
    private String errorCode;
    private String error;
    /** The conversation as it was before this turn, so a later turn can supersede this one. */
    private Map<String, Object> stateBefore;
    private Integer transcriptSizeBefore;
    private List<String> openCallIdsBefore;
    private Instant createdAt;
    private Instant finishedAt;

    /** A PENDING row for a submitted turn. */
    public static AiCall pending(TurnRequest r, String conversationId, String provider, String model, Instant now) {
        AiCall c = new AiCall();
        c.setWorkspaceId(r.workspaceId());
        c.setConversationId(conversationId);
        c.setRunId(r.runId());
        c.setChatbotId(r.chatbotId());
        c.setNodeName(r.nodeName());
        c.setProfileId(r.profileId());
        c.setProvider(provider);
        c.setModel(model);
        c.setRequest(JSON.convertValue(r, new TypeReference<Map<String, Object>>() {}));
        c.setStatus(AiCallStatus.PENDING);
        c.setInput(r.inputs() == null ? "" : String.join("\n", r.inputs()));
        c.setCreatedAt(now);
        return c;
    }

    public TurnRequest turnRequest() {
        return JSON.convertValue(request, TurnRequest.class);
    }

    public void fail(String code, String message) {
        status = AiCallStatus.FAILED;
        errorCode = code;
        error = message;
    }

    /** The result the listeners get; {@code json} is the reply parsed when the request asked for JSON. */
    public TurnResult toResult() {
        TurnStatus s = status == AiCallStatus.DONE ? TurnStatus.DONE : TurnStatus.FAILED;
        return new TurnResult(id, s, reply, s == TurnStatus.DONE ? json() : null, finish,
                usage == null ? new AiUsage(0, 0, 0, 0) : usage, toolsUsed, conversationId, runId, errorCode, error);
    }

    private Map<String, Object> json() {
        AiReplyFormat f = turnRequest().replyFormat();
        if (f == null || f.type() != AiReplyFormat.Type.JSON || reply == null || reply.isBlank()) return null;
        try {
            return JSON.readValue(reply, new TypeReference<Map<String, Object>>() {});
        } catch (RuntimeException e) {
            return null;
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getChatbotId() { return chatbotId; }
    public void setChatbotId(String chatbotId) { this.chatbotId = chatbotId; }
    public String getNodeName() { return nodeName; }
    public void setNodeName(String nodeName) { this.nodeName = nodeName; }
    public String getProfileId() { return profileId; }
    public void setProfileId(String profileId) { this.profileId = profileId; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Map<String, Object> getRequest() { return request; }
    public void setRequest(Map<String, Object> request) { this.request = request; }
    public AiCallStatus getStatus() { return status; }
    public void setStatus(AiCallStatus status) { this.status = status; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(Instant leaseUntil) { this.leaseUntil = leaseUntil; }
    public Instant getDeliveredAt() { return deliveredAt; }
    public void setDeliveredAt(Instant deliveredAt) { this.deliveredAt = deliveredAt; }
    public String getInput() { return input; }
    public void setInput(String input) { this.input = input; }
    public String getReply() { return reply; }
    public void setReply(String reply) { this.reply = reply; }
    public AiFinish getFinish() { return finish; }
    public void setFinish(AiFinish finish) { this.finish = finish; }
    public List<ToolCallLog> getToolCalls() { return toolCalls; }
    public void setToolCalls(List<ToolCallLog> toolCalls) { this.toolCalls = toolCalls; }
    public AiUsage getUsage() { return usage; }
    public void setUsage(AiUsage usage) { this.usage = usage; }
    public boolean isToolsUsed() { return toolsUsed; }
    public void setToolsUsed(boolean toolsUsed) { this.toolsUsed = toolsUsed; }
    public Long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Long latencyMs) { this.latencyMs = latencyMs; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public Map<String, Object> getStateBefore() { return stateBefore; }
    public void setStateBefore(Map<String, Object> stateBefore) { this.stateBefore = stateBefore; }
    public Integer getTranscriptSizeBefore() { return transcriptSizeBefore; }
    public void setTranscriptSizeBefore(Integer transcriptSizeBefore) { this.transcriptSizeBefore = transcriptSizeBefore; }
    public List<String> getOpenCallIdsBefore() { return openCallIdsBefore; }
    public void setOpenCallIdsBefore(List<String> openCallIdsBefore) { this.openCallIdsBefore = openCallIdsBefore; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
}
