package me.nageshk.omnichannel.crm.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.activity.ActivityType;
import me.nageshk.omnichannel.crm.deal.DealStatus;
import me.nageshk.omnichannel.crm.pipeline.CrmPipeline;
import me.nageshk.omnichannel.crm.pipeline.CrmStage;
import me.nageshk.omnichannel.crm.report.dto.StageDurationView;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;

/**
 * Spec C12. A move out of a stage of the pipeline counts when its STAGE_CHANGED entry carries
 * {@code fromStageEnteredAt}: in-pipeline moves by the activity's {@code pipelineId}, pipeline changes by
 * {@code payload.fromPipelineId}. Open deals add how long they have sat in their current stage.
 */
@Component
public class StageDurationReport {

    static final double DAY_MS = 86_400_000d;
    private static final String FROM_ENTERED = "payload.fromStageEnteredAt";

    private final MongoTemplate mongo;
    private final ReportScope scope;
    private final Clock clock;

    StageDurationReport(MongoTemplate mongo, ReportScope scope, Clock clock) {
        this.mongo = mongo;
        this.scope = scope;
        this.clock = clock;
    }

    public StageDurationView build(CrmContext ctx, ResolvedFilter filter, CrmPipeline pipeline) {
        String pid = pipeline.getId();
        Criteria fromThisPipeline = new Criteria().orOperator(
                Criteria.where("payload.fromPipelineId").is(pid),
                new Criteria().andOperator(Criteria.where("payload.fromPipelineId").exists(false),
                        Criteria.where("pipelineId").is(pid)));
        // the pipeline clause is the one above, not the filter's: a move out of this pipeline is stamped with the target
        Criteria moves = scope.activities(ctx, filter.withPipeline(null))
                .and("type").is(ActivityType.STAGE_CHANGED.name())
                .and(FROM_ENTERED).exists(true)
                .andOperator(fromThisPipeline);
        AggregationOperation project = c -> new Document("$project", new Document("stage", "$payload.fromStageId")
                .append("happenedAt", 1)
                .append("ms", new Document("$subtract", List.of(new Document("$toLong", "$happenedAt"),
                        new Document("$toLong", "$" + FROM_ENTERED)))));
        Map<String, List<Double>> days = new HashMap<>();
        Instant since = null;
        for (Document d : ReportRunner.run(mongo, ActivitySummaryReport.ACTIVITIES,
                List.of(Aggregation.match(moves), project))) {
            Object ms = d.get("ms");
            if (d.getString("stage") == null || !(ms instanceof Number n) || n.longValue() < 0) continue;
            days.computeIfAbsent(d.getString("stage"), k -> new ArrayList<>()).add(n.doubleValue() / DAY_MS);
            Instant at = ((Date) d.get("happenedAt")).toInstant();
            since = since == null || at.isBefore(since) ? at : since;
        }

        Map<String, long[]> openCount = new HashMap<>();
        Map<String, List<Double>> openDays = new HashMap<>();
        long now = Instant.now(clock).toEpochMilli();
        AggregationOperation openProject = c -> new Document("$project",
                new Document("stageId", 1).append("stageEnteredAt", 1));
        for (Document d : ReportRunner.run(mongo, PipelineSummaryReport.DEALS, List.of(
                Aggregation.match(scope.deals(ctx, filter.withPipeline(pid)).and("status").is(DealStatus.OPEN.name())),
                openProject))) {
            String stageId = d.getString("stageId");
            openCount.computeIfAbsent(stageId, k -> new long[1])[0]++;
            if (d.get("stageEnteredAt") instanceof Date entered) {
                openDays.computeIfAbsent(stageId, k -> new ArrayList<>()).add((now - entered.getTime()) / DAY_MS);
            }
        }

        List<StageDurationView.Stage> stages = pipeline.getStages().stream()
                .sorted(Comparator.comparingDouble(CrmStage::getPosition))
                .map(s -> {
                    List<Double> moved = days.getOrDefault(s.getId(), List.of());
                    return new StageDurationView.Stage(s.getId(), s.getName(), moved.size(), average(moved),
                            median(moved), openCount.containsKey(s.getId()) ? openCount.get(s.getId())[0] : 0,
                            average(openDays.getOrDefault(s.getId(), List.of())));
                }).toList();
        return new StageDurationView(pid, pipeline.getName(), since, stages);
    }

    /** The middle value (mean of the middle two for an even count), 1 decimal; null when empty. */
    static Double median(List<Double> values) {
        if (values.isEmpty()) return null;
        List<Double> sorted = values.stream().sorted().toList();
        int mid = sorted.size() / 2;
        double m = sorted.size() % 2 == 1 ? sorted.get(mid) : (sorted.get(mid - 1) + sorted.get(mid)) / 2;
        return round(m);
    }

    /** The mean, 1 decimal; null when empty. */
    static Double average(List<Double> values) {
        return values.isEmpty() ? null : round(values.stream().mapToDouble(Double::doubleValue).average().orElse(0));
    }

    private static double round(double v) {
        return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
