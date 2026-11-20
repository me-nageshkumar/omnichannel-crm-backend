package me.nageshk.omnichannel.service.chatbot.report;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.db.chatbot.RunEnding;

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

    /** One run behind a report number; names are looked up per page, deal fields are null without an add-to-CRM step. */
    public record RunRow(String runId, String contactName, String phone, String numberLabel, Instant startedAt,
            Instant finishedAt, String status, RunEnding ending, String agentName, String teamName,
            Map<String, Object> progress, String chatId, String dealId, String dealPipelineId, String leadOwnerName) {
    }

    public record RunRowPage(List<RunRow> items, long total, int page, int size) {
    }
}
