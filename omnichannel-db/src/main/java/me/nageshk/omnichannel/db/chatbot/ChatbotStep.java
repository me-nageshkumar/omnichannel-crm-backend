package me.nageshk.omnichannel.db.chatbot;

import java.time.Instant;
import java.util.Map;

/** One node's execution in a run: what it did, which output it took, what it waited for, what it produced. */
public record ChatbotStep(int index, String nodeId, String nodeName, String type, Instant startedAt, Instant finishedAt,
        Outcome outcome, String output, String summary, String error, Map<String, Object> result, Waiting waitedFor) {

    public enum Outcome { DONE, WAITING, FAILED }
}
