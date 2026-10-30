package me.nageshk.omnichannel.crm.report;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.activity.ActivityType;
import me.nageshk.omnichannel.crm.activity.ActorType;
import me.nageshk.omnichannel.crm.report.dto.ActivitySummaryView;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;

/** Spec C11. One aggregation over the period's activities by a person, grouped by actor and type. */
@Component
public class ActivitySummaryReport {

    static final String ACTIVITIES = "crmActivities";
    static final List<ActivityType> COUNTED = List.of(ActivityType.TASK_COMPLETED, ActivityType.NOTE_ADDED,
            ActivityType.STAGE_CHANGED, ActivityType.DEAL_WON, ActivityType.DEAL_LOST, ActivityType.CALL_LOGGED,
            ActivityType.MEETING_LOGGED, ActivityType.EMAIL_LOGGED);

    private final MongoTemplate mongo;
    private final ReportScope scope;

    ActivitySummaryReport(MongoTemplate mongo, ReportScope scope) {
        this.mongo = mongo;
        this.scope = scope;
    }

    public ActivitySummaryView build(CrmContext ctx, ResolvedFilter f, Function<String, String> names) {
        Criteria c = scope.activities(ctx, f).and("actorType").is(ActorType.USER.name())
                .and("type").in(COUNTED.stream().map(Enum::name).toList());
        // The owner filter already puts actorId on the criteria (Spring allows one expression per key); without it
        // the system actor is excluded here, with it the loop below drops a "system" owner.
        if (f.ownerIds().isEmpty()) c = c.and("actorId").ne("system");
        AggregationOperation group = x -> new Document("$group",
                new Document("_id", new Document("actor", "$actorId").append("type", "$type"))
                        .append("count", new Document("$sum", 1)));
        Map<String, EnumMap<ActivityType, Long>> byActor = new LinkedHashMap<>();
        for (Document d : ReportRunner.run(mongo, ACTIVITIES, List.of(Aggregation.match(c), group))) {
            Document id = d.get("_id", Document.class);
            String actor = id.getString("actor");
            if (actor == null || "system".equals(actor)) continue;
            ActivityType type;
            try {
                type = ActivityType.valueOf(id.getString("type"));
            } catch (IllegalArgumentException | NullPointerException unknown) {
                continue;
            }
            byActor.computeIfAbsent(actor, k -> new EnumMap<>(ActivityType.class))
                    .merge(type, ((Number) d.get("count")).longValue(), Long::sum);
        }
        List<ActivitySummaryView.Row> rows = new ArrayList<>();
        byActor.forEach((actor, n) -> rows.add(new ActivitySummaryView.Row(actor, names.apply(actor),
                n.getOrDefault(ActivityType.TASK_COMPLETED, 0L), n.getOrDefault(ActivityType.NOTE_ADDED, 0L),
                n.getOrDefault(ActivityType.STAGE_CHANGED, 0L), n.getOrDefault(ActivityType.DEAL_WON, 0L),
                n.getOrDefault(ActivityType.DEAL_LOST, 0L), n.getOrDefault(ActivityType.CALL_LOGGED, 0L),
                n.getOrDefault(ActivityType.MEETING_LOGGED, 0L), n.getOrDefault(ActivityType.EMAIL_LOGGED, 0L))));
        rows.sort(Comparator.comparingLong((ActivitySummaryView.Row r) ->
                r.tasksCompleted() + r.stageMoves() + r.dealsWon()).reversed()
                .thenComparing(ActivitySummaryView.Row::label));
        return new ActivitySummaryView(rows);
    }
}
