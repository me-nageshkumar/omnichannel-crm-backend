package me.nageshk.omnichannel.service.billing.money;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** The Indian financial year, April to March, as it is written on documents: "2026-27". */
public final class FinancialYear {

    private FinancialYear() {}

    public static String of(Instant at, ZoneId zone) {
        LocalDate day = at.atZone(zone).toLocalDate();
        int start = day.getMonthValue() >= 4 ? day.getYear() : day.getYear() - 1;
        return start + "-" + String.format("%02d", (start + 1) % 100);
    }
}
