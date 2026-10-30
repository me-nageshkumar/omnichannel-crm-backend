package me.nageshk.omnichannel.crm.report.dto;

import java.time.Instant;
import java.util.List;
import me.nageshk.omnichannel.crm.report.ReportBucket;

/** Spec C6: created, won and lost per bucket, zero-filled over the range. */
public record DealsOverTimeView(ReportBucket bucket, String currency, List<Point> points, long otherCurrencyDeals) {

    public record Point(Instant start, long created, long won, long lost, double wonAmount) {}
}
