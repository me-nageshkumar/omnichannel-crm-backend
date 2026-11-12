package me.nageshk.omnichannel.db.chatbot;

/** Spec §5.1: two live states, then the final ones. */
public enum ChatbotRunStatus {
    RUNNING, WAITING, COMPLETED, ENDED, STOPPED, HANDED_OVER, JUMPED, FAILED;

    public boolean active() {
        return this == RUNNING || this == WAITING;
    }
}
