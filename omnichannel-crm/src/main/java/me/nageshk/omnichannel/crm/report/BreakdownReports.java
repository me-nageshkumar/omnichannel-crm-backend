package me.nageshk.omnichannel.crm.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.contact.OriginKind;
import me.nageshk.omnichannel.crm.deal.DealStatus;
import me.nageshk.omnichannel.crm.report.dto.ByNumberView;
import me.nageshk.omnichannel.crm.report.dto.ByOwnerView;
import me.nageshk.omnichannel.crm.report.dto.BySourceView;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;

/**
 * Deals broken down by one field — owner, source or number (spec C7–C9) — plus contacts by origin. Each breakdown is
 * two aggregations over the deals the caller may see: one over OPEN deals now, one over deals closed in the period
 * (status, currency), and for created counts a third over {@code creationTime} in the period.
 */
@Component
public class BreakdownReports {

    private static final String CONTACTS = "crmContacts";

    /** The figures of one key of a breakdown. */
    static final class Tally {
        long openCount, wonCount, lostCount, created;
        BigDecimal openAmount = BigDecimal.ZERO, wonAmount = BigDecimal.ZERO;
    }

    /** Tallies by key (null key → ""), and how many matched deals were in another currency. */
    record Breakdown(Map<String, Tally> byKey, long otherCurrencyDeals) {}

    private final MongoTemplate mongo;
    private final ReportScope scope;

    BreakdownReports(MongoTemplate mongo, ReportScope scope) {
        this.mongo = mongo;
        this.scope = scope;
    }

    public ByOwnerView byOwner(CrmContext ctx, ResolvedFilter f, String currency, Function<String, String> names) {
        Breakdown b = breakdown(ctx, f, "ownerId", currency, false);
        List<ByOwnerView.Row> rows = new ArrayList<>();
        b.byKey().forEach((key, t) -> {
            long decided = t.wonCount + t.lostCount;
            Double winRate = decided == 0 ? null : BigDecimal.valueOf(t.wonCount)
                    .divide(BigDecimal.valueOf(decided), 3, RoundingMode.HALF_UP).doubleValue();
            rows.add(new ByOwnerView.Row(key, key.isEmpty() ? "Unassigned" : names.apply(key), t.openCount,
                    ReportMoney.scaled(t.openAmount), t.wonCount, ReportMoney.scaled(t.wonAmount), t.lostCount,
                    winRate));
        });
        rows.sort(Comparator.comparingDouble(ByOwnerView.Row::wonAmount).reversed()
                .thenComparing(ByOwnerView.Row::label));
        return new ByOwnerView(currency, rows, b.otherCurrencyDeals());
    }

    public BySourceView bySource(CrmContext ctx, ResolvedFilter f, String currency, Function<String, String> labels) {
        Breakdown b = breakdown(ctx, f, "source", currency, true);
        List<BySourceView.Deal> deals = new ArrayList<>();
        b.byKey().forEach((key, t) -> deals.add(new BySourceView.Deal(key, key.isEmpty() ? "Unspecified"
                : labels.apply(key), t.created, t.wonCount, ReportMoney.scaled(t.wonAmount))));
        deals.sort(Comparator.comparingLong(BySourceView.Deal::won).reversed()
                .thenComparing(Comparator.comparingLong(BySourceView.Deal::created).reversed())
                .thenComparing(BySourceView.Deal::label));

        Map<OriginKind, Long> origins = new LinkedHashMap<>();
        for (OriginKind k : OriginKind.values()) origins.put(k, 0L);
        Criteria created = scope.contacts(ctx, f).andOperator(ReportScope.inRange("creationTime", f));
        AggregationOperation group = c -> new Document("$group", new Document("_id", "$origin.kind")
                .append("count", new Document("$sum", 1)));
        for (Document d : ReportRunner.run(mongo, CONTACTS, List.of(Aggregation.match(created), group))) {
            String kind = d.getString("_id");
            if (kind == null) continue;
            try {
                origins.computeIfPresent(OriginKind.valueOf(kind), (k, n) -> n + ((Number) d.get("count")).longValue());
            } catch (IllegalArgumentException unknown) {
                // a kind this build does not know: left out
            }
        }
        List<BySourceView.Contact> contacts = origins.entrySet().stream()
                .map(e -> new BySourceView.Contact(e.getKey(), e.getValue())).toList();
        return new BySourceView(currency, deals, contacts, b.otherCurrencyDeals());
    }

    public ByNumberView byNumber(CrmContext ctx, ResolvedFilter f, String currency, Function<String, String> labels) {
        Breakdown b = breakdown(ctx, f, "channel.phoneNumberId", currency, true);
        List<ByNumberView.Row> rows = new ArrayList<>();
        b.byKey().forEach((key, t) -> rows.add(new ByNumberView.Row(key, labels.apply(key), t.created, t.wonCount,
                ReportMoney.scaled(t.wonAmount), ReportMoney.scaled(t.openAmount))));
        rows.sort(Comparator.comparingDouble(ByNumberView.Row::wonAmount).reversed()
                .thenComparing(ByNumberView.Row::label));
        return new ByNumberView(currency, rows, b.otherCurrencyDeals());
    }

    /**
     * Open deals now and deals closed in the period, grouped by {@code field} (plus the status and currency); with
     * {@code withCreated}, also deals created in the period.
     */
    Breakdown breakdown(CrmContext ctx, ResolvedFilter f, String field, String currency, boolean withCreated) {
        Map<String, Tally> byKey = new LinkedHashMap<>();
        long[] other = new long[1];
        AggregationOperation group = c -> new Document("$group", new Document("_id",
                new Document("key", new Document("$ifNull", List.of("$" + field, ""))).append("status", "$status")
                        .append("currency", new Document("$ifNull", List.of("$currency", currency))))
                .append("count", new Document("$sum", 1))
                .append("amount", new Document("$sum", new Document("$ifNull", List.of("$amount", 0)))));

        Criteria open = scope.deals(ctx, f).and("status").is(DealStatus.OPEN.name());
        for (Document d : ReportRunner.run(mongo, PipelineSummaryReport.DEALS, List.of(Aggregation.match(open), group))) {
            Document id = d.get("_id", Document.class);
            Tally t = byKey.computeIfAbsent(key(id), k -> new Tally());
            long n = ((Number) d.get("count")).longValue();
            t.openCount += n;
            if (currency.equals(id.getString("currency"))) t.openAmount = t.openAmount.add(ReportMoney.toBigDecimal(d.get("amount")));
            else other[0] += n;
        }
        Criteria closed = scope.deals(ctx, f).and("status").in(DealStatus.WON.name(), DealStatus.LOST.name())
                .andOperator(ReportScope.inRange("closedAt", f));
        for (Document d : ReportRunner.run(mongo, PipelineSummaryReport.DEALS, List.of(Aggregation.match(closed), group))) {
            Document id = d.get("_id", Document.class);
            Tally t = byKey.computeIfAbsent(key(id), k -> new Tally());
            long n = ((Number) d.get("count")).longValue();
            boolean won = DealStatus.WON.name().equals(id.getString("status"));
            if (won) t.wonCount += n;
            else t.lostCount += n;
            if (!currency.equals(id.getString("currency"))) other[0] += n;
            else if (won) t.wonAmount = t.wonAmount.add(ReportMoney.toBigDecimal(d.get("amount")));
        }
        if (withCreated) {
            Criteria created = scope.deals(ctx, f).andOperator(ReportScope.inRange("creationTime", f));
            AggregationOperation byKeyOnly = c -> new Document("$group",
                    new Document("_id", new Document("$ifNull", List.of("$" + field, "")))
                            .append("count", new Document("$sum", 1)));
            for (Document d : ReportRunner.run(mongo, PipelineSummaryReport.DEALS,
                    List.of(Aggregation.match(created), byKeyOnly))) {
                Object k = d.get("_id");
                byKey.computeIfAbsent(k == null ? "" : k.toString(), x -> new Tally()).created +=
                        ((Number) d.get("count")).longValue();
            }
        }
        return new Breakdown(byKey, other[0]);
    }

    private static String key(Document id) {
        Object k = id.get("key");
        return k == null ? "" : k.toString();
    }
}
