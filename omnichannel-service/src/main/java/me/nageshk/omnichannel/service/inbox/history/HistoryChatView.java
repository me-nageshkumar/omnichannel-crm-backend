package me.nageshk.omnichannel.service.inbox.history;

import java.time.Instant;
import me.nageshk.omnichannel.db.inbox.HistoryChatStatus;
import me.nageshk.omnichannel.db.inbox.HistorySkipReason;
import me.nageshk.omnichannel.db.inbox.WaWebHistoryImportChat;

/** {@code chatId} is the inbox chat for the remote id, when there is one. */
public record HistoryChatView(String id, String remoteId, String requestedPhone, String chatName, String chatId,
        HistoryChatStatus status, HistorySkipReason skipReason, String error, WaWebHistoryImportChat.Counts counts,
        boolean capped, Instant attemptedAt, Instant finishedAt) {
}
