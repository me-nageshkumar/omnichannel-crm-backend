package me.nageshk.omnichannel.service.automation;

import java.util.List;

/** What stops a test from running, and what its actions will really do; {@code canRun} when nothing blocks. */
public record PreflightView(boolean canRun, List<Check> checks) {

    public enum Level {
        BLOCKER, WARNING
    }

    /** {@code actionIndex} is null for a check about the whole rule. */
    public record Check(Level level, Integer actionIndex, String message) {
    }
}
