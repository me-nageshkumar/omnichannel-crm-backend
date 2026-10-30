package me.nageshk.omnichannel.crm.report.dto;

import java.util.List;

/** Spec C11: what each person did in the period, from the timeline; busiest first. */
public record ActivitySummaryView(List<Row> rows) {

    public record Row(String actorId, String label, long tasksCompleted, long notesAdded, long stageMoves,
            long dealsWon, long dealsLost, long calls, long meetings, long emails) {}
}
