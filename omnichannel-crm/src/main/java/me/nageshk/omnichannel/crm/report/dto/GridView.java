package me.nageshk.omnichannel.crm.report.dto;

import java.time.Instant;
import java.util.List;
import me.nageshk.omnichannel.crm.common.TargetType;
import me.nageshk.omnichannel.crm.report.ReportBucket;

/**
 * Spec C14. Rows per group (and per bucket when bucketed), largest first; past 100 groups the rest fold into one
 * row with key {@code __other}. {@code value} is the metric (count, sum or average), {@code count} the records.
 */
public record GridView(TargetType entityType, String groupBy, String metric, String field, ReportBucket dateBucket,
        String currency, List<Row> rows, boolean truncated, long otherCurrencyDeals) {

    public static final String OTHER = "__other";

    public record Row(String key, String label, Instant bucket, double value, long count) {}
}
