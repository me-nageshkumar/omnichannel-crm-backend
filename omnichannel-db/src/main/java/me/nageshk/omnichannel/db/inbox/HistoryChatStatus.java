package me.nageshk.omnichannel.db.inbox;

/** Progress of one chat in a history import. */
public enum HistoryChatStatus {
    PENDING, RUNNING, DONE, SKIPPED, FAILED
}
