package me.nageshk.omnichannel.crm.report;

import java.util.List;

/**
 * The filter every report takes (spec C3), as sent: ISO instants (default the last 90 days), an IANA zone (default
 * UTC) that decides day, week and month boundaries, and optional narrowing by pipeline, number and owner.
 */
public record ReportFilter(String from, String to, String tz, String pipelineId, List<String> phoneNumberIds,
        List<String> ownerIds) {

    public static final ReportFilter EMPTY = new ReportFilter(null, null, null, null, null, null);
}
