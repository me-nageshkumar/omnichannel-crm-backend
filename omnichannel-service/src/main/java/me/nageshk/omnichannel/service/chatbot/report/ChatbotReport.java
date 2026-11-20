package me.nageshk.omnichannel.service.chatbot.report;

import java.util.List;
import java.util.Map;

/** The chatbot report (lead-owner report spec §6.4): how many runs, where they stopped, how far conversations got. */
public record ChatbotReport(Totals totals, List<StepRow> steps, List<ProgressRow> progress) {

    public record Totals(long started, long running, long completed, long wentSilent, long stopped, long failed,
            long handedOver, long jumped, ChatTotals chat) {
    }

    /** Where the chats went; {@code notRecorded} counts finished runs that ended before endings were kept. */
    public record ChatTotals(long toAgent, long toQueue, long closed, long left, long notRecorded) {
    }

    public record StepRow(String nodeId, String name, String type, boolean removed, long reached,
            Map<String, Long> endedHere) {
    }

    /** {@code byCount[k]}: runs that ended at the node having given exactly k of its fields. */
    public record ProgressRow(String nodeId, List<FieldCount> fields, List<Long> byCount) {
    }

    public record FieldCount(String key, String label, long given) {
    }
}
