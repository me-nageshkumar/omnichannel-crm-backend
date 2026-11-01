package me.nageshk.omnichannel.db.inbox;

import java.time.Instant;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/** One chat of a history import and how far it has been read. */
@Document("waWebHistoryImportChats")
@CompoundIndexes({
        @CompoundIndex(name = "import_remote", def = "{'importId': 1, 'remoteId': 1}", unique = true),
        @CompoundIndex(name = "import_status", def = "{'importId': 1, 'status': 1, '_id': 1}"),
        @CompoundIndex(name = "status_attempted", def = "{'status': 1, 'attemptedAt': 1}")
})
@Getter
@Setter
public class WaWebHistoryImportChat {

    @Id
    private String id;
    private String importId;
    private String workspaceId;
    private String phoneNumberId;
    /** WhatsApp chat id, {@code <digits>@c.us} or {@code <id>@lid}. */
    private String remoteId;
    /** The phone the requester typed, when the chat was named by phone. */
    private String requestedPhone;
    private String chatName;
    private HistoryChatStatus status;
    private HistorySkipReason skipReason;
    private String error;
    private Cursor cursor;
    private Counts counts;
    /** True when the per-chat message cap stopped the import of this chat. */
    private boolean capped;
    /** True when WhatsApp Web stopped giving older messages although it did not say there are none. */
    private boolean olderUnavailable;
    private int emptyPages;
    /** Pages in a row that stored nothing, loaded or not; at {@code maxIdlePages} the chat ends. */
    private int idlePages;
    /** Pages of this chat that timed out; at {@code maxTimeoutsPerChat} the chat fails as TIMEOUT. */
    private int timeouts;
    private Instant attemptedAt;
    private Instant finishedAt;

    /** Where the next page starts: messages before this timestamp, minus the ids already seen at it. */
    public record Cursor(Long beforeT, List<String> excludeIds) {
    }

    public record Counts(long seen, long inserted, long updated) {

        public static final Counts ZERO = new Counts(0, 0, 0);
    }
}
