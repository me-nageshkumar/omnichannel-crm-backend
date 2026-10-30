package me.nageshk.omnichannel.crm.report;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;

/** A {@link ReportFilter} after validation: the range is {@code [from, to)}, the sets never null. */
public record ResolvedFilter(Instant from, Instant to, ZoneId zone, String pipelineId, Set<String> phoneNumberIds,
        Set<String> ownerIds) {

    public ResolvedFilter {
        phoneNumberIds = Set.copyOf(phoneNumberIds);
        ownerIds = Set.copyOf(ownerIds);
    }

    public ResolvedFilter withPipeline(String id) {
        return new ResolvedFilter(from, to, zone, id, phoneNumberIds, ownerIds);
    }
}
