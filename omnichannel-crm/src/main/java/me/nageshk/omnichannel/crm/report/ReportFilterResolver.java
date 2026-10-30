package me.nageshk.omnichannel.crm.report;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.access.PipelineIds;
import me.nageshk.omnichannel.crm.platform.PlatformNumber;
import org.springframework.stereotype.Component;

/** Checks a {@link ReportFilter} against the rules of spec C3 and the caller's access. */
@Component
public class ReportFilterResolver {

    static final Duration DEFAULT_RANGE = Duration.ofDays(90);
    static final Duration MAX_RANGE = Duration.ofDays(731);

    private final PipelineIds pipelineIds;
    private final Clock clock;

    public ReportFilterResolver(PipelineIds pipelineIds, Clock clock) {
        this.pipelineIds = pipelineIds;
        this.clock = clock;
    }

    public ResolvedFilter resolve(CrmContext ctx, ReportFilter f) {
        ReportFilter in = f == null ? ReportFilter.EMPTY : f;
        Instant now = Instant.now(clock);
        Instant to = instant(in.to(), "to", now);
        Instant from = instant(in.from(), "from", to.minus(DEFAULT_RANGE));
        if (!from.isBefore(to)) throw ReportErrors.invalid("from", "must be before to");
        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw ReportErrors.invalid("to", "the range may be at most 731 days");
        }
        ZoneId zone = zone(in.tz());
        String pipelineId = blank(in.pipelineId()) ? null : in.pipelineId().trim();
        if (pipelineId != null && (!ctx.pipelines().allows(pipelineId)
                || !pipelineIds.existing(ctx.workspaceId()).contains(pipelineId))) {
            throw ReportErrors.invalid("pipelineId", "is not a pipeline you can see");
        }
        Set<String> numbers = clean(in.phoneNumberIds());
        Set<String> known = new LinkedHashSet<>();
        for (PlatformNumber n : ctx.numbers()) known.add(n.id());
        for (String id : numbers) {
            if (!known.contains(id)) throw ReportErrors.invalid("phoneNumberIds", "is not a number of this brand");
        }
        return new ResolvedFilter(from, to, zone, pipelineId, numbers, clean(in.ownerIds()));
    }

    private static Instant instant(String raw, String field, Instant fallback) {
        if (blank(raw)) return fallback;
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw ReportErrors.invalid(field, "must be an ISO-8601 instant");
        }
    }

    /** An IANA region id, or UTC; offsets and GMT+n are not places and have no day boundaries of their own. */
    private static ZoneId zone(String raw) {
        if (blank(raw)) return ZoneId.of("UTC");
        String id = raw.trim();
        try {
            ZoneId zone = ZoneId.of(id);
            boolean region = !(zone instanceof ZoneOffset) && !zone.getId().startsWith("GMT")
                    && !zone.getId().startsWith("UT") && !zone.getId().equals("Z");
            if (region || zone.getId().equals("UTC")) return zone;
        } catch (DateTimeException e) {
            // reported below
        }
        throw ReportErrors.invalid("tz", "must be an IANA time zone such as Asia/Kolkata");
    }

    private static Set<String> clean(List<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw != null) raw.stream().filter(s -> !blank(s)).map(String::trim).forEach(out::add);
        return out;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
