package me.nageshk.omnichannel.db.inbox;

/** What happened in a chat, as recorded in its audit trail. */
public enum ActionType {
    ROUTED, ASSIGNED, UNASSIGNED, CLOSED, REOPENED, TRANSFERRED
}
