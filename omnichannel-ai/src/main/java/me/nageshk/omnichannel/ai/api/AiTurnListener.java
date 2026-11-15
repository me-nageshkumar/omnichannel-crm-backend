package me.nageshk.omnichannel.ai.api;

/** The service implements it; the worker calls every bean when a turn finishes. */
public interface AiTurnListener {

    void turnFinished(TurnResult r);
}
