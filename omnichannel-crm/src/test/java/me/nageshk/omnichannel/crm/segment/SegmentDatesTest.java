package me.nageshk.omnichannel.crm.segment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;

class SegmentDatesTest {

    // 2026-03-15 18:30 in Kolkata (13:00Z)
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-15T13:00:00Z"), ZoneId.of("UTC"));
    static final SegmentDates DATES = new SegmentDates(CLOCK, ZoneId.of("Asia/Kolkata"));

    static Document doc(Criteria c) {
        return c.getCriteriaObject();
    }

    @Test
    void beforeAfterBetweenAreDayAlignedInZone() {
        assertThat(DATES.startOfDay("2026-03-10")).isEqualTo(Instant.parse("2026-03-09T18:30:00Z"));
        assertThat(DATES.startOfNextDay("2026-03-10")).isEqualTo(Instant.parse("2026-03-10T18:30:00Z"));
        Document before = doc(DATES.dateCriteria("creationTime", "before", "2026-03-10"));
        assertThat(before.get("creationTime", Document.class).get("$lt")).isEqualTo(Instant.parse("2026-03-09T18:30:00Z"));
        Document after = doc(DATES.dateCriteria("creationTime", "after", "2026-03-10"));
        assertThat(after.get("creationTime", Document.class).get("$gte")).isEqualTo(Instant.parse("2026-03-10T18:30:00Z"));
        Document between = doc(DATES.dateCriteria("creationTime", "between", List.of("2026-03-10", "2026-03-12")));
        Document range = between.get("creationTime", Document.class);
        assertThat(range.get("$gte")).isEqualTo(Instant.parse("2026-03-09T18:30:00Z"));
        assertThat(range.get("$lt")).isEqualTo(Instant.parse("2026-03-12T18:30:00Z"));
        assertThat(doc(DATES.dateCriteria("creationTime", "isSet", null)).get("creationTime", Document.class).get("$ne")).isNull();
        assertThat(doc(DATES.dateCriteria("creationTime", "isNotSet", null)).get("creationTime")).isNull();
    }

    @Test
    void withinLastDaysAndMoreThanDaysAgo() {
        assertThat(DATES.daysAgo(30)).isEqualTo(Instant.parse("2026-02-13T13:00:00Z"));
        Document within = doc(DATES.dateCriteria("lastActivityAt", "withinLastDays", "30"));
        assertThat(within.get("lastActivityAt", Document.class).get("$gte")).isEqualTo(Instant.parse("2026-02-13T13:00:00Z"));
        Document older = doc(DATES.dateCriteria("lastActivityAt", "moreThanDaysAgo", "30"));
        assertThat(older.get("lastActivityAt", Document.class).get("$lt")).isEqualTo(Instant.parse("2026-02-13T13:00:00Z"));
        // the day of "n days ago" in the zone, for day-string fields and the channel port
        assertThat(DATES.dayAgo(30)).isEqualTo("2026-02-13");
        assertThat(DATES.today()).isEqualTo("2026-03-15");
    }

    @Test
    void dayStringCompareIsLexicographic() {
        assertThat(doc(DATES.dayStringCriteria("expectedCloseDate", "before", "2026-03-10"))
                .get("expectedCloseDate", Document.class).get("$lt")).isEqualTo("2026-03-10");
        assertThat(doc(DATES.dayStringCriteria("expectedCloseDate", "after", "2026-03-10"))
                .get("expectedCloseDate", Document.class).get("$gt")).isEqualTo("2026-03-10");
        Document between = doc(DATES.dayStringCriteria("expectedCloseDate", "between", List.of("2026-03-10", "2026-03-12")))
                .get("expectedCloseDate", Document.class);
        assertThat(between.get("$gte")).isEqualTo("2026-03-10");
        assertThat(between.get("$lte")).isEqualTo("2026-03-12");
        assertThat(doc(DATES.dayStringCriteria("expectedCloseDate", "withinLastDays", "30"))
                .get("expectedCloseDate", Document.class).get("$gte")).isEqualTo("2026-02-13");
        assertThat(doc(DATES.dayStringCriteria("expectedCloseDate", "moreThanDaysAgo", "30"))
                .get("expectedCloseDate", Document.class).get("$lt")).isEqualTo("2026-02-13");
    }
}
