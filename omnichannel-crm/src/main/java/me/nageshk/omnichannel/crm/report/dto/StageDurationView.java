package me.nageshk.omnichannel.crm.report.dto;

import java.time.Instant;
import java.util.List;

/**
 * Spec C12: days spent per stage, from the moves recorded since {@code since} (null when none counted), plus the
 * deals open in each stage now.
 */
public record StageDurationView(String pipelineId, String pipelineName, Instant since, List<Stage> stages) {

    public record Stage(String stageId, String name, long movesOut, Double averageDays, Double medianDays,
            long openNow, Double averageDaysOpenNow) {}
}
