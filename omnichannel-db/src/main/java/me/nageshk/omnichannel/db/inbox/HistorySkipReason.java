package me.nageshk.omnichannel.db.inbox;

/** Why a chat of a history import was skipped. */
public enum HistorySkipReason {
    NO_CHAT,
    /** A number moved to this brand and the chat holds nothing from the move on. */
    NOTHING_AFTER_MOVE
}
