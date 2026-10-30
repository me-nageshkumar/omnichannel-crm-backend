package me.nageshk.omnichannel.crm.report;

import me.nageshk.omnichannel.crm.access.CrmAccess;
import me.nageshk.omnichannel.crm.report.dto.DealsOverTimeRequest;
import me.nageshk.omnichannel.crm.report.dto.DealsOverTimeView;
import me.nageshk.omnichannel.crm.report.dto.PipelineSummaryView;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The reports (spec phase C §5); every call is a POST with the shared filter, open to anyone with crm:view. */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/crm/reports")
public class CrmReportController {

    private static final String VIEW = "@access.has(#workspaceId, 'crm:view')";

    private final CrmAccess crmAccess;
    private final CrmReportService reports;

    CrmReportController(CrmAccess crmAccess, CrmReportService reports) {
        this.crmAccess = crmAccess;
        this.reports = reports;
    }

    @PostMapping("/pipeline-summary")
    @PreAuthorize(VIEW)
    public PipelineSummaryView pipelineSummary(@AuthenticationPrincipal AuthenticatedUser me,
            @PathVariable String workspaceId, @RequestBody(required = false) ReportFilter filter) {
        return reports.pipelineSummary(crmAccess.resolve(me, workspaceId), filter);
    }

    @PostMapping("/deals-over-time")
    @PreAuthorize(VIEW)
    public DealsOverTimeView dealsOverTime(@AuthenticationPrincipal AuthenticatedUser me,
            @PathVariable String workspaceId, @RequestBody(required = false) DealsOverTimeRequest req) {
        return reports.dealsOverTime(crmAccess.resolve(me, workspaceId), req);
    }
}
