package me.nageshk.omnichannel.db.inbox;

/** Life cycle of a chat history import. */
public enum HistoryImportStatus {
    QUEUED, RUNNING, PAUSED, COMPLETED, CANCELLED, FAILED
}
