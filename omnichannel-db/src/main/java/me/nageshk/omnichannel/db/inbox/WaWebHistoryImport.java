package me.nageshk.omnichannel.db.inbox;

import java.time.Instant;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/** One request to import the chat history of a WhatsApp Web number. */
@Document("waWebHistoryImports")
@CompoundIndexes({
        @CompoundIndex(name = "phone_scope_active", def = "{'phoneNumberId': 1, 'scopeKey': 1}", unique = true,
                partialFilter = "{'active': true}"),
        @CompoundIndex(name = "phone_status", def = "{'phoneNumberId': 1, 'status': 1}"),
        @CompoundIndex(name = "ws_created", def = "{'workspaceId': 1, 'creationTime': -1}")
})
@Getter
@Setter
public class WaWebHistoryImport {

    @Id
    private String id;
    private String customerId;
    private String workspaceId;
    private String phoneNumberId;
    private Scope scope;
    private Range range;
    /** Identifies the scope, so two active imports of the same chats on one number are rejected. */
    private String scopeKey;
    /** True while the import is queued or running; the unique index covers active imports only. */
    private boolean active;
    private HistoryImportStatus status;
    private HistoryWaiting waiting;
    private Counts counts;
    private String requestedByUserId;
    private String requestedByName;
    private String error;
    @CreatedDate
    private Instant creationTime;
    private Instant startedAt;
    private Instant finishedAt;
    @LastModifiedDate
    private Instant lastUpdateTime;
    @Version
    private Long version;

    /** The chats to import: all of them, or those named by remote id or phone. */
    public record Scope(HistoryScopeKind kind, List<String> remoteIds, List<String> phones) {
    }

    /** Only messages within this window are imported; a null bound is open. */
    public record Range(Instant from, Instant to) {
    }

    public record Counts(int chatsTotal, int chatsDone, int chatsSkipped, int chatsFailed, long messagesSeen,
            long messagesInserted, long messagesUpdated) {

        public static final Counts ZERO = new Counts(0, 0, 0, 0, 0, 0, 0);
    }
}
