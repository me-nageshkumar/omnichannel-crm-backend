package me.nageshk.omnichannel.crm.segment;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.data.mongodb.core.query.Criteria;

/**
 * Date arithmetic for segment rules: {@code YYYY-MM-DD} values are days in the brand's zone, day counts hang off
 * the clock. Instant fields get instants; {@code YYYY-MM-DD} string fields (a deal's expected close, DATE custom
 * fields) get day strings compared lexicographically.
 */
public final class SegmentDates {

    private final Clock clock;
    private final ZoneId zone;

    public SegmentDates(Clock clock, ZoneId zone) {
        this.clock = clock;
        this.zone = zone;
    }

    public Instant startOfDay(String yyyyMmDd) {
        return LocalDate.parse(yyyyMmDd.trim()).atStartOfDay(zone).toInstant();
    }

    public Instant startOfNextDay(String yyyyMmDd) {
        return LocalDate.parse(yyyyMmDd.trim()).plusDays(1).atStartOfDay(zone).toInstant();
    }

    public Instant daysAgo(int n) {
        return clock.instant().minus(n, ChronoUnit.DAYS);
    }

    /** Today's date in the zone. */
    public String today() {
        return LocalDate.ofInstant(clock.instant(), zone).toString();
    }

    /** The date {@code n} days ago in the zone. */
    public String dayAgo(int n) {
        return LocalDate.ofInstant(clock.instant(), zone).minusDays(n).toString();
    }

    /** Criteria on an Instant field. */
    public Criteria dateCriteria(String field, String operator, Object value) {
        Criteria c = Criteria.where(field);
        return switch (operator) {
            case "before" -> c.lt(startOfDay((String) value));
            case "after" -> c.gte(startOfNextDay((String) value));
            case "between" -> {
                List<?> l = (List<?>) value;
                yield c.gte(startOfDay((String) l.get(0))).lt(startOfNextDay((String) l.get(1)));
            }
            case "withinLastDays" -> c.gte(daysAgo(days(value)));
            case "moreThanDaysAgo" -> c.lt(daysAgo(days(value)));
            case "isSet" -> c.ne(null);
            case "isNotSet" -> c.is(null);
            default -> throw new IllegalArgumentException("not a date operator: " + operator);
        };
    }

    /** Criteria on a {@code YYYY-MM-DD} string field. */
    public Criteria dayStringCriteria(String field, String operator, Object value) {
        Criteria c = Criteria.where(field);
        return switch (operator) {
            case "before" -> c.lt(((String) value).trim());
            case "after" -> c.gt(((String) value).trim());
            case "between" -> {
                List<?> l = (List<?>) value;
                yield c.gte(((String) l.get(0)).trim()).lte(((String) l.get(1)).trim());
            }
            case "withinLastDays" -> c.gte(dayAgo(days(value)));
            case "moreThanDaysAgo" -> c.lt(dayAgo(days(value)));
            case "isSet" -> c.ne(null);
            case "isNotSet" -> c.is(null);
            default -> throw new IllegalArgumentException("not a date operator: " + operator);
        };
    }

    /** Criteria on an ISO-instant string field (DATETIME custom fields): the zone-day boundaries as strings. */
    public Criteria instantStringCriteria(String field, String operator, Object value) {
        Criteria c = Criteria.where(field);
        return switch (operator) {
            case "before" -> c.lt(startOfDay((String) value).toString());
            case "after" -> c.gte(startOfNextDay((String) value).toString());
            case "between" -> {
                List<?> l = (List<?>) value;
                yield c.gte(startOfDay((String) l.get(0)).toString()).lt(startOfNextDay((String) l.get(1)).toString());
            }
            case "withinLastDays" -> c.gte(daysAgo(days(value)).toString());
            case "moreThanDaysAgo" -> c.lt(daysAgo(days(value)).toString());
            case "isSet" -> c.ne(null);
            case "isNotSet" -> c.is(null);
            default -> throw new IllegalArgumentException("not a date operator: " + operator);
        };
    }

    static int days(Object value) {
        return Integer.parseInt(((String) value).trim());
    }
}
