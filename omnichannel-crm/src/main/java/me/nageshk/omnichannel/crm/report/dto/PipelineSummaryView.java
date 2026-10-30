package me.nageshk.omnichannel.crm.report.dto;

import java.util.List;
import me.nageshk.omnichannel.crm.pipeline.StageType;

/** Spec C5: open deals per stage now, and the period's outcomes. Money in {@code currency}. */
public record PipelineSummaryView(String pipelineId, String pipelineName, String currency, List<Stage> stages,
        long openCount, double openAmount, Outcome won, Outcome lost, Double winRate, Double averageWonAmount,
        long otherCurrencyDeals) {

    public record Stage(String stageId, String name, StageType type, long openCount, double openAmount,
            double weightedAmount) {}

    public record Outcome(long count, double amount) {}
}
