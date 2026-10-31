package me.nageshk.omnichannel.service.inbox.history;

import java.time.Instant;
import java.util.List;
import me.nageshk.omnichannel.db.inbox.HistoryImportStatus;
import me.nageshk.omnichannel.db.inbox.HistoryScopeKind;
import me.nageshk.omnichannel.db.inbox.HistoryWaiting;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImport;

public record HistoryImportView(String id, NumberRef phoneNumber, ScopeView scope, WaWebHistoryImport.Range range,
        HistoryImportStatus status, HistoryWaiting waiting, WaWebHistoryImport.Counts counts, Ref requestedBy,
        Instant creationTime, Instant startedAt, Instant finishedAt, Integer progressPercent, String error) {

    public record Ref(String id, String name) {
    }

    public record NumberRef(String id, String number, String label) {
    }

    public record ScopeView(HistoryScopeKind kind, List<String> phones, int chatCount) {
    }
}
