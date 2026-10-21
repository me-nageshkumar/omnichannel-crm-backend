package me.nageshk.omnichannel.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class CampaignEstimatesTest {

    static final Instant WED_11_IST = Instant.parse("2026-10-21T05:30:00Z");
    final CampaignSettings s = settings();

    static CampaignSettings settings() {
        CampaignSettings s = new CampaignSettings();
        s.setTimezone("Asia/Kolkata");
        s.setWorkingDays(List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY, DayOfWeek.SATURDAY));
        s.setStartTime("10:00");
        s.setEndTime("18:00");
        return s;
    }

    @Test
    void countsTodayWhileTheDayIsOpen() {
        assertThat(CampaignEstimates.of(100, 35, s, WED_11_IST))
                .isEqualTo(new Estimate(100, 35, 3, LocalDate.of(2026, 10, 23)));
    }

    @Test
    void afterHoursStartsTomorrow() {
        assertThat(CampaignEstimates.of(100, 35, s, Instant.parse("2026-10-21T13:30:00Z")).finishBy())
                .isEqualTo(LocalDate.of(2026, 10, 24));
    }

    @Test
    void sundayIsSkipped() {
        assertThat(CampaignEstimates.of(10, 5, s, Instant.parse("2026-10-24T13:30:00Z")).finishBy())
                .isEqualTo(LocalDate.of(2026, 10, 27));
    }

    @Test
    void noSenderMeansNoDays() {
        Estimate e = CampaignEstimates.of(10, 0, s, WED_11_IST);
        assertThat(e.days()).isNull();
        assertThat(e.finishBy()).isNull();
    }

    @Test
    void nothingLeftIsZeroDays() {
        Estimate e = CampaignEstimates.of(0, 0, s, WED_11_IST);
        assertThat(e.days()).isZero();
        assertThat(e.finishBy()).isNull();
    }
}
