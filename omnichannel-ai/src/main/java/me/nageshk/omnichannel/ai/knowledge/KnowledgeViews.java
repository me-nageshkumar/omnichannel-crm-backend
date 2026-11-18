package me.nageshk.omnichannel.ai.knowledge;

import java.time.Instant;
import java.util.List;
import me.nageshk.omnichannel.ai.connection.AiConnection;

/** The wire shapes of knowledge bases and their sources. */
public final class KnowledgeViews {

    private KnowledgeViews() {}

    public record SaveBase(String name, String description, String connectionId, Long version) {}

    public record NoteIn(String title, String text) {}

    public record WebPageIn(String url) {}

    /** {@code action} is FETCH_AGAIN or RETRY; title and text edit a note. */
    public record SourceChange(String title, String text, String action) {}

    public record Counts(int queued, int adding, int ready, int failed) {}

    public record BaseView(String id, String name, String description, String connectionId, String connectionName,
            String provider, String providerStoreId, KnowledgeBaseStatus status, String error, Counts counts,
            int sourceCount, List<String> usedBy, Instant createdAt, Instant updatedAt, Long version) {

        static BaseView of(KnowledgeBase b, AiConnection connection, List<KnowledgeSource> sources, List<String> usedBy) {
            int queued = 0, adding = 0, ready = 0, failed = 0;
            for (KnowledgeSource s : sources) {
                switch (s.getState()) {
                    case QUEUED -> queued++;
                    case UPLOADING, INDEXING -> adding++;
                    case READY -> ready++;
                    case FAILED -> failed++;
                    case REMOVING -> { }
                }
            }
            return new BaseView(b.getId(), b.getName(), b.getDescription(), b.getConnectionId(),
                    connection == null ? null : connection.getName(), b.getProvider(), b.getProviderStoreId(),
                    b.getStatus(), b.getError(), new Counts(queued, adding, ready, failed),
                    queued + adding + ready + failed, usedBy,
                    b.getCreatedAt(), b.getUpdatedAt(), b.getVersion());
        }
    }

    public record SourceView(String id, SourceKind kind, String title, String url, String fileName,
            String contentType, long size, SourceState state, String error, Instant addedAt, String addedBy,
            Instant fetchedAt, Long version) {

        static SourceView of(KnowledgeSource s) {
            return new SourceView(s.getId(), s.getKind(), s.getTitle(), s.getUrl(), s.getFileName(),
                    s.getContentType(), s.getSize(), s.getState(), s.getError(), s.getAddedAt(), s.getAddedBy(),
                    s.getFetchedAt(), s.getVersion());
        }
    }
}
