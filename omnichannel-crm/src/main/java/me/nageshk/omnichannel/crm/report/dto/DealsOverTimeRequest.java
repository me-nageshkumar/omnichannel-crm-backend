package me.nageshk.omnichannel.crm.report.dto;

import java.util.List;
import me.nageshk.omnichannel.crm.report.ReportFilter;

/** The shared filter plus an optional bucket (DAY, WEEK or MONTH; chosen from the range when absent). */
public record DealsOverTimeRequest(String from, String to, String tz, String pipelineId, List<String> phoneNumberIds,
        List<String> ownerIds, String bucket) {

    public ReportFilter filter() {
        return new ReportFilter(from, to, tz, pipelineId, phoneNumberIds, ownerIds);
    }
}
