package me.nageshk.omnichannel.crm.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.report.dto.GridView;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;

/**
 * Spec C14. One aggregation: the entity's scope and date range, grouped by the group path (plus the time bucket and,
 * for money, the currency), with a count and a sum. Groups are ranked by value, then count; past 100 they fold into
 * "Other".
 */
@Component
public class GridReport {

    /** A group's figures over all its buckets. */
    private static final class Group {
        final String key;
        final Map<Instant, double[]> byBucket = new LinkedHashMap<>(); // [value, count]
        double value;
        long count;

        Group(String key) {
            this.key = key;
        }
    }

    private final MongoTemplate mongo;
    private final ReportScope scope;

    GridReport(MongoTemplate mongo, ReportScope scope) {
        this.mongo = mongo;
        this.scope = scope;
    }

    public GridView build(CrmContext ctx, ResolvedFilter f, GridFields.Resolved r, String currency,
            Function<String, String> labels) {
        Criteria base = switch (r.entity()) {
            case DEAL -> scope.deals(ctx, f);
            case CONTACT -> scope.contacts(ctx, f);
            default -> scope.companies(ctx, f);
        };
        Criteria match = base.andOperator(ReportScope.inRange(r.dateField(), f));
        Document id = new Document("g", new Document("$ifNull", List.of("$" + r.groupPath(), "")));
        if (r.bucket() != null) id.append("b", ReportBuckets.truncExpression(r.dateField(), r.bucket(), f.zone()));
        if (r.money()) id.append("c", new Document("$ifNull", List.of("$currency", currency)));
        Document group = new Document("_id", id).append("count", new Document("$sum", 1));
        if (r.valuePath() != null) {
            // only records that hold the value count towards a sum or an average
            group.append("sum", new Document("$sum", new Document("$ifNull", List.of("$" + r.valuePath(), 0))))
                    .append("valued", new Document("$sum", new Document("$cond", List.of(
                            new Document("$eq", List.of(new Document("$type", "$" + r.valuePath()), "missing")),
                            0, 1))));
        }
        AggregationOperation groupOp = c -> new Document("$group", group);

        Map<String, Group> groups = new LinkedHashMap<>();
        long other = 0;
        for (Document d : ReportRunner.run(mongo, r.collection(), List.of(Aggregation.match(match), groupOp))) {
            Document gid = d.get("_id", Document.class);
            String key = String.valueOf(gid.get("g") == null ? "" : gid.get("g"));
            long count = ((Number) d.get("count")).longValue();
            if (r.money() && !currency.equals(gid.getString("c"))) {
                other += count;
                continue;
            }
            Instant bucket = gid.get("b") instanceof Date date ? date.toInstant() : null;
            double value;
            if ("COUNT".equals(r.metric())) {
                value = count;
            } else {
                BigDecimal sum = ReportMoney.toBigDecimal(d.get("sum"));
                long valued = ((Number) d.get("valued")).longValue();
                value = "SUM".equals(r.metric()) ? ReportMoney.scaled(sum)
                        : valued == 0 ? 0 : ReportMoney.scaled(sum.divide(BigDecimal.valueOf(valued), 2, RoundingMode.HALF_UP));
            }
            Group g = groups.computeIfAbsent(key, Group::new);
            double[] cell = g.byBucket.computeIfAbsent(bucket, k -> new double[2]);
            cell[0] += value;
            cell[1] += count;
            g.value += value;
            g.count += count;
        }

        List<Group> ranked = new ArrayList<>(groups.values());
        ranked.sort(Comparator.comparingDouble((Group g) -> g.value).reversed()
                .thenComparing(Comparator.comparingLong((Group g) -> g.count).reversed())
                .thenComparing(g -> g.key));
        boolean truncated = ranked.size() > GridFields.MAX_GROUPS;
        List<Group> kept = truncated ? ranked.subList(0, GridFields.MAX_GROUPS) : ranked;
        List<GridView.Row> rows = new ArrayList<>();
        for (Group g : kept) {
            String label = labels.apply(g.key);
            g.byBucket.forEach((bucket, cell) -> rows.add(new GridView.Row(g.key, label, bucket,
                    "AVG".equals(r.metric()) ? avg(cell) : round(cell[0]), (long) cell[1])));
        }
        if (truncated) {
            Group rest = new Group(GridView.OTHER);
            for (Group g : ranked.subList(GridFields.MAX_GROUPS, ranked.size())) {
                g.byBucket.forEach((bucket, cell) -> {
                    double[] sum = rest.byBucket.computeIfAbsent(bucket, k -> new double[2]);
                    sum[0] += cell[0];
                    sum[1] += cell[1];
                });
            }
            rest.byBucket.forEach((bucket, cell) -> rows.add(new GridView.Row(GridView.OTHER, "Other", bucket,
                    "AVG".equals(r.metric()) ? avg(cell) : round(cell[0]), (long) cell[1])));
        }
        if (r.bucket() != null) {
            // by period, then by the group's rank within it
            Map<String, Integer> rank = new LinkedHashMap<>();
            kept.forEach(g -> rank.put(g.key, rank.size()));
            rank.put(GridView.OTHER, rank.size());
            rows.sort(Comparator.comparing((GridView.Row x) -> x.bucket() == null ? Instant.EPOCH : x.bucket())
                    .thenComparingInt(x -> rank.getOrDefault(x.key(), Integer.MAX_VALUE)));
        }
        return new GridView(r.entity(), groupByName(r), r.metric(), r.valuePath() == null ? null
                : r.valuePath().startsWith("customFields.") ? GridFields.CUSTOM_PREFIX
                        + r.valuePath().substring("customFields.".length()) : r.valuePath(),
                r.bucket(), currency, rows, truncated, other);
    }

    private static String groupByName(GridFields.Resolved r) {
        return r.groupField() != null ? GridFields.CUSTOM_PREFIX + r.groupField().getKey() : r.groupPath();
    }

    /** A folded "Other" cell holds the sum of its parts' averages; the mean of those is the honest figure. */
    private static double avg(double[] cell) {
        return round(cell[0]);
    }

    private static double round(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
