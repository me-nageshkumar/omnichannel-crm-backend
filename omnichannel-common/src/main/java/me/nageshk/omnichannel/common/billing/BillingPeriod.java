package me.nageshk.omnichannel.common.billing;

import java.time.LocalDate;
import java.time.YearMonth;

/** How long one term of a plan lasts. */
public enum BillingPeriod {
    MONTHLY(1), QUARTERLY(3), HALF_YEARLY(6), YEARLY(12);

    private final int months;

    BillingPeriod(int months) {
        this.months = months;
    }

    public int months() { return months; }

    /**
     * The first day after a term that starts on {@code start}: the anchor day of the month {@code period} months
     * later, or that month's last day when it is shorter. The anchor is the day of month the first term started on,
     * so a term from 31 Jan ends 28 Feb and the next one ends 31 Mar.
     */
    public static LocalDate endOf(LocalDate start, BillingPeriod period, int anchorDay) {
        YearMonth month = YearMonth.from(start).plusMonths(period.months);
        return month.atDay(Math.min(anchorDay, month.lengthOfMonth()));
    }
}
