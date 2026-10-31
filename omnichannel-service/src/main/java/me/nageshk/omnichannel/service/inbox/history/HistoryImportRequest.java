package me.nageshk.omnichannel.service.inbox.history;

import java.time.Instant;
import java.util.List;
import me.nageshk.omnichannel.db.inbox.HistoryScopeKind;

/** A request to import chat history, for all chats of a number or for named ones. */
public record HistoryImportRequest(ScopeRequest scope, Instant from, Instant to) {

    public record ScopeRequest(HistoryScopeKind kind, List<String> phones, List<String> contactIds) {
    }
}
