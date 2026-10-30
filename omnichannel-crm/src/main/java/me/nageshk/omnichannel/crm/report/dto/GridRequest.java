package me.nageshk.omnichannel.crm.report.dto;

import java.util.List;
import me.nageshk.omnichannel.crm.report.ReportFilter;

/**
 * The grid report (spec C14): the shared filter plus what to group, what to measure and whether to bucket by time.
 * {@code groupBy} is a built-in key or {@code custom:<key>}; {@code field} is {@code amount} or {@code custom:<key>}.
 */
public record GridRequest(String from, String to, String tz, String pipelineId, List<String> phoneNumberIds,
        List<String> ownerIds, String entityType, String groupBy, String metric, String field, String dateBucket,
        String dateField) {

    public ReportFilter filter() {
        return new ReportFilter(from, to, tz, pipelineId, phoneNumberIds, ownerIds);
    }
}
