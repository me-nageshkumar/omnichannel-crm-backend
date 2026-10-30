package me.nageshk.omnichannel.crm.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.deal.DealStatus;
import me.nageshk.omnichannel.crm.pipeline.CrmPipeline;
import me.nageshk.omnichannel.crm.pipeline.CrmStage;
import me.nageshk.omnichannel.crm.report.dto.PipelineSummaryView;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;

/**
 * Spec C5. One aggregation over the pipeline's deals grouped by stage, status, currency and probability; open deals
 * are counted now, won and lost ones by {@code closedAt} in the period. Weighting uses the deal's probability, else
 * the stage's.
 */
@Component
public class PipelineSummaryReport {

    static final String DEALS = "crmDeals";

    private final MongoTemplate mongo;
    private final ReportScope scope;

    PipelineSummaryReport(MongoTemplate mongo, ReportScope scope) {
        this.mongo = mongo;
        this.scope = scope;
    }

    public PipelineSummaryView build(CrmContext ctx, ResolvedFilter filter, CrmPipeline pipeline,
            String defaultCurrency) {
        ResolvedFilter f = filter.withPipeline(pipeline.getId());
        Criteria open = scope.deals(ctx, f).and("status").is(DealStatus.OPEN.name());
        Criteria closed = scope.deals(ctx, f).and("status").in(DealStatus.WON.name(), DealStatus.LOST.name())
                .andOperator(ReportScope.inRange("closedAt", f));
        AggregationOperation group = c -> new Document("$group", new Document("_id",
                new Document("stageId", "$stageId").append("status", "$status")
                        .append("currency", new Document("$ifNull", List.of("$currency", defaultCurrency)))
                        .append("probability", "$probability"))
                .append("count", new Document("$sum", 1))
                .append("amount", new Document("$sum", new Document("$ifNull", List.of("$amount", 0)))));

        Map<String, long[]> openCount = new HashMap<>();
        Map<String, BigDecimal[]> openMoney = new HashMap<>(); // [amount, weighted]
        long other = 0;
        for (Document d : ReportRunner.run(mongo, DEALS, List.of(Aggregation.match(open), group))) {
            Document id = d.get("_id", Document.class);
            String stageId = id.getString("stageId");
            long n = ((Number) d.get("count")).longValue();
            openCount.computeIfAbsent(stageId, k -> new long[1])[0] += n;
            if (!defaultCurrency.equals(id.getString("currency"))) {
                other += n;
                continue;
            }
            BigDecimal amount = ReportMoney.toBigDecimal(d.get("amount"));
            Integer probability = id.get("probability") instanceof Number p ? p.intValue() : null;
            Integer stageProbability = stageProbability(pipeline, stageId);
            BigDecimal[] money = openMoney.computeIfAbsent(stageId, k -> new BigDecimal[] {BigDecimal.ZERO,
                BigDecimal.ZERO});
            money[0] = money[0].add(amount);
            money[1] = money[1].add(ReportMoney.weighted(amount, probability, stageProbability));
        }

        long wonCount = 0, lostCount = 0;
        BigDecimal wonAmount = BigDecimal.ZERO, lostAmount = BigDecimal.ZERO;
        for (Document d : ReportRunner.run(mongo, DEALS, List.of(Aggregation.match(closed), group))) {
            Document id = d.get("_id", Document.class);
            long n = ((Number) d.get("count")).longValue();
            boolean won = DealStatus.WON.name().equals(id.getString("status"));
            if (won) wonCount += n;
            else lostCount += n;
            if (!defaultCurrency.equals(id.getString("currency"))) {
                other += n;
                continue;
            }
            BigDecimal amount = ReportMoney.toBigDecimal(d.get("amount"));
            if (won) wonAmount = wonAmount.add(amount);
            else lostAmount = lostAmount.add(amount);
        }

        List<PipelineSummaryView.Stage> stages = pipeline.getStages().stream()
                .sorted(Comparator.comparingDouble(CrmStage::getPosition))
                .map(s -> {
                    BigDecimal[] money = openMoney.getOrDefault(s.getId(), new BigDecimal[] {BigDecimal.ZERO,
                        BigDecimal.ZERO});
                    return new PipelineSummaryView.Stage(s.getId(), s.getName(), s.getType(),
                            openCount.containsKey(s.getId()) ? openCount.get(s.getId())[0] : 0,
                            ReportMoney.scaled(money[0]), ReportMoney.scaled(money[1]));
                }).toList();
        long totalOpen = stages.stream().mapToLong(PipelineSummaryView.Stage::openCount).sum();
        BigDecimal totalOpenAmount = openMoney.values().stream().map(m -> m[0]).reduce(BigDecimal.ZERO, BigDecimal::add);
        long decided = wonCount + lostCount;
        Double winRate = decided == 0 ? null
                : BigDecimal.valueOf(wonCount).divide(BigDecimal.valueOf(decided), 3, RoundingMode.HALF_UP).doubleValue();
        Double averageWon = wonCount == 0 ? null
                : ReportMoney.scaled(wonAmount.divide(BigDecimal.valueOf(wonCount), 2, RoundingMode.HALF_UP));
        return new PipelineSummaryView(pipeline.getId(), pipeline.getName(), defaultCurrency, stages, totalOpen,
                ReportMoney.scaled(totalOpenAmount), new PipelineSummaryView.Outcome(wonCount, ReportMoney.scaled(wonAmount)),
                new PipelineSummaryView.Outcome(lostCount, ReportMoney.scaled(lostAmount)), winRate, averageWon, other);
    }

    private static Integer stageProbability(CrmPipeline pipeline, String stageId) {
        return pipeline.getStages().stream().filter(s -> s.getId().equals(stageId)).map(CrmStage::getProbability)
                .findFirst().orElse(null);
    }
}
