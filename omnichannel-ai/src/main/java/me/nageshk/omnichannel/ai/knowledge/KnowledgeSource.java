package me.nageshk.omnichannel.ai.knowledge;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/** One file, web page or note of a knowledge base; the row is also the queue item the worker claims. */
@Document("aiKnowledgeSources")
@CompoundIndexes({
        @CompoundIndex(name = "state_lease", def = "{'state': 1, 'leaseUntil': 1}"),
        @CompoundIndex(name = "knowledgeBase", def = "{'knowledgeBaseId': 1}")})
public class KnowledgeSource {

    @Id
    private String id;
    private String workspaceId;
    private String knowledgeBaseId;
    private SourceKind kind;
    private String title;
    private String url;
    private String fileName;
    private String contentType;
    private long size;
    /** Our copy in GridFS (hex ObjectId); web pages have none until fetched. */
    private String gridFsId;
    private String providerFileId;
    private SourceState state = SourceState.QUEUED;
    private String error;
    private int attempts;
    private Instant leaseUntil;
    private Instant nextCheckAt;
    private Instant addedAt;
    private String addedBy;
    private Instant fetchedAt;
    /** When the provider started indexing the file; the worker gives up a set time after it. */
    private Instant indexingSince;
    /** Seconds until the next state check; doubles from 2 to 30 while the provider is still indexing. */
    private int checkDelaySeconds;
    @Version
    private Long version;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(String knowledgeBaseId) { this.knowledgeBaseId = knowledgeBaseId; }
    public SourceKind getKind() { return kind; }
    public void setKind(SourceKind kind) { this.kind = kind; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }
    public String getGridFsId() { return gridFsId; }
    public void setGridFsId(String gridFsId) { this.gridFsId = gridFsId; }
    public String getProviderFileId() { return providerFileId; }
    public void setProviderFileId(String providerFileId) { this.providerFileId = providerFileId; }
    public SourceState getState() { return state; }
    public void setState(SourceState state) { this.state = state; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(Instant leaseUntil) { this.leaseUntil = leaseUntil; }
    public Instant getNextCheckAt() { return nextCheckAt; }
    public void setNextCheckAt(Instant nextCheckAt) { this.nextCheckAt = nextCheckAt; }
    public Instant getAddedAt() { return addedAt; }
    public void setAddedAt(Instant addedAt) { this.addedAt = addedAt; }
    public String getAddedBy() { return addedBy; }
    public void setAddedBy(String addedBy) { this.addedBy = addedBy; }
    public Instant getFetchedAt() { return fetchedAt; }
    public void setFetchedAt(Instant fetchedAt) { this.fetchedAt = fetchedAt; }
    public Instant getIndexingSince() { return indexingSince; }
    public void setIndexingSince(Instant indexingSince) { this.indexingSince = indexingSince; }
    public int getCheckDelaySeconds() { return checkDelaySeconds; }
    public void setCheckDelaySeconds(int checkDelaySeconds) { this.checkDelaySeconds = checkDelaySeconds; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
