package me.nageshk.omnichannel.crm.report;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.common.PicklistItem;
import me.nageshk.omnichannel.crm.report.dto.LifecycleFunnelView;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.stereotype.Component;

/**
 * Spec C10: a snapshot of the caller's contacts per lifecycle status, in picklist order — active statuses by
 * position, then inactive ones, then "Not set". The date range does not apply; the other filters do.
 */
@Component
public class LifecycleFunnelReport {

    private final MongoTemplate mongo;
    private final ReportScope scope;

    LifecycleFunnelReport(MongoTemplate mongo, ReportScope scope) {
        this.mongo = mongo;
        this.scope = scope;
    }

    public LifecycleFunnelView build(CrmContext ctx, ResolvedFilter f, List<PicklistItem> statuses) {
        Map<String, Long> counts = new HashMap<>();
        AggregationOperation group = c -> new Document("$group",
                new Document("_id", new Document("$ifNull", List.of("$lifecycleStatus", "")))
                        .append("count", new Document("$sum", 1)));
        for (Document d : ReportRunner.run(mongo, "crmContacts",
                List.of(Aggregation.match(scope.contacts(ctx, f)), group))) {
            counts.merge(String.valueOf(d.get("_id")), ((Number) d.get("count")).longValue(), Long::sum);
        }
        List<PicklistItem> items = statuses == null ? List.of() : statuses;
        List<LifecycleFunnelView.Row> rows = new ArrayList<>();
        items.stream().sorted(Comparator.comparing((PicklistItem i) -> !i.active())
                .thenComparingInt(PicklistItem::position))
                .forEach(i -> rows.add(new LifecycleFunnelView.Row(i.key(), label(i), i.active(),
                        counts.getOrDefault(i.key(), 0L))));
        // values no picklist item knows (a key removed by hand) still count, after the known ones
        counts.keySet().stream().filter(k -> !k.isEmpty() && items.stream().noneMatch(i -> i.key().equals(k)))
                .sorted().forEach(k -> rows.add(new LifecycleFunnelView.Row(k, k, false, counts.get(k))));
        rows.add(new LifecycleFunnelView.Row("", "Not set", false, counts.getOrDefault("", 0L)));
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        return new LifecycleFunnelView(rows, total);
    }

    private static String label(PicklistItem i) {
        return i.label() == null || i.label().isBlank() ? i.key() : i.label();
    }
}
