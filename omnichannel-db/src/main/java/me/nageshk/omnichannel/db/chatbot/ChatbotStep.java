package me.nageshk.omnichannel.db.chatbot;

import java.time.Instant;
import java.util.Map;

/** One node's execution in a run: what it did, which output it took, what it waited for, what it produced, and what the conversation has collected so far (null when nothing). */
public record ChatbotStep(int index, String nodeId, String nodeName, String type, Instant startedAt, Instant finishedAt,
        Outcome outcome, String output, String summary, String error, Map<String, Object> result, Waiting waitedFor,
        Map<String, Object> progress) {

    public enum Outcome { DONE, WAITING, FAILED }
}
