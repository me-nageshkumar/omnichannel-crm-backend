package me.nageshk.omnichannel.crm.report;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.deal.DealStatus;
import me.nageshk.omnichannel.crm.report.dto.DealsOverTimeView;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;

/**
 * Spec C6. Two aggregations — deals by {@code creationTime} bucket, and closed deals by {@code closedAt} bucket with
 * their status and currency — folded onto the zero-filled bucket list.
 */
@Component
public class DealsOverTimeReport {

    private final MongoTemplate mongo;
    private final ReportScope scope;

    DealsOverTimeReport(MongoTemplate mongo, ReportScope scope) {
        this.mongo = mongo;
        this.scope = scope;
    }

    public DealsOverTimeView build(CrmContext ctx, ResolvedFilter f, ReportBucket bucket, String defaultCurrency) {
        Map<Instant, long[]> counts = new LinkedHashMap<>(); // [created, won, lost]
        Map<Instant, BigDecimal> wonAmount = new LinkedHashMap<>();
        for (Instant start : ReportBuckets.starts(f.from(), f.to(), f.zone(), bucket)) {
            counts.put(start, new long[3]);
            wonAmount.put(start, BigDecimal.ZERO);
        }
        long other = 0;

        Criteria created = scope.deals(ctx, f).andOperator(ReportScope.inRange("creationTime", f));
        AggregationOperation byCreation = c -> new Document("$group",
                new Document("_id", ReportBuckets.truncExpression("creationTime", bucket, f.zone()))
                        .append("count", new Document("$sum", 1)));
        for (Document d : ReportRunner.run(mongo, PipelineSummaryReport.DEALS,
                List.of(Aggregation.match(created), byCreation))) {
            long[] c = counts.get(start(d));
            if (c != null) c[0] += ((Number) d.get("count")).longValue();
        }

        Criteria closed = scope.deals(ctx, f).and("status").in(DealStatus.WON.name(), DealStatus.LOST.name())
                .andOperator(ReportScope.inRange("closedAt", f));
        AggregationOperation byClose = c -> new Document("$group", new Document("_id",
                new Document("bucket", ReportBuckets.truncExpression("closedAt", bucket, f.zone()))
                        .append("status", "$status")
                        .append("currency", new Document("$ifNull", List.of("$currency", defaultCurrency))))
                .append("count", new Document("$sum", 1))
                .append("amount", new Document("$sum", new Document("$ifNull", List.of("$amount", 0)))));
        for (Document d : ReportRunner.run(mongo, PipelineSummaryReport.DEALS,
                List.of(Aggregation.match(closed), byClose))) {
            Document id = d.get("_id", Document.class);
            Instant start = ((Date) id.get("bucket")).toInstant();
            long n = ((Number) d.get("count")).longValue();
            boolean won = DealStatus.WON.name().equals(id.getString("status"));
            long[] c = counts.get(start);
            if (c != null) c[won ? 1 : 2] += n;
            if (!defaultCurrency.equals(id.getString("currency"))) {
                other += n;
            } else if (won && wonAmount.containsKey(start)) {
                wonAmount.put(start, wonAmount.get(start).add(ReportMoney.toBigDecimal(d.get("amount"))));
            }
        }

        List<DealsOverTimeView.Point> points = counts.entrySet().stream()
                .map(e -> new DealsOverTimeView.Point(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2],
                        ReportMoney.scaled(wonAmount.get(e.getKey()))))
                .toList();
        return new DealsOverTimeView(bucket, defaultCurrency, points, other);
    }

    private static Instant start(Document d) {
        Object id = d.get("_id");
        return id instanceof Date date ? date.toInstant() : null;
    }
}
