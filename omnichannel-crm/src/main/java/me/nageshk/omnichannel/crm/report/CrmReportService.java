package me.nageshk.omnichannel.crm.report;

import java.util.Locale;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.pipeline.CrmPipeline;
import me.nageshk.omnichannel.crm.report.dto.DealsOverTimeRequest;
import me.nageshk.omnichannel.crm.report.dto.DealsOverTimeView;
import me.nageshk.omnichannel.crm.report.dto.PipelineSummaryView;
import me.nageshk.omnichannel.crm.settings.CrmSettingsService;
import org.springframework.stereotype.Service;

/** The reports (spec phase C): resolves the filter and the brand's defaults, then delegates to one report each. */
@Service
public class CrmReportService {

    private final ReportFilterResolver resolver;
    private final ReportScope scope;
    private final CrmSettingsService settings;
    private final PipelineSummaryReport pipelineSummary;
    private final DealsOverTimeReport dealsOverTime;

    CrmReportService(ReportFilterResolver resolver, ReportScope scope, CrmSettingsService settings,
            PipelineSummaryReport pipelineSummary, DealsOverTimeReport dealsOverTime) {
        this.resolver = resolver;
        this.scope = scope;
        this.settings = settings;
        this.pipelineSummary = pipelineSummary;
        this.dealsOverTime = dealsOverTime;
    }

    public PipelineSummaryView pipelineSummary(CrmContext ctx, ReportFilter filter) {
        ResolvedFilter f = resolver.resolve(ctx, filter);
        CrmPipeline pipeline = scope.pipelineOrDefault(ctx, f.pipelineId());
        return pipelineSummary.build(ctx, f, pipeline, currency(ctx));
    }

    public DealsOverTimeView dealsOverTime(CrmContext ctx, DealsOverTimeRequest req) {
        DealsOverTimeRequest r = req == null ? new DealsOverTimeRequest(null, null, null, null, null, null, null) : req;
        ResolvedFilter f = resolver.resolve(ctx, r.filter());
        ReportBucket bucket = bucket(r.bucket(), f);
        return dealsOverTime.build(ctx, f, bucket, currency(ctx));
    }

    /** The brand's default currency as the reporting currency (spec C4). */
    String currency(CrmContext ctx) {
        return ReportMoney.currencyOf(null, settings.find(ctx.workspaceId()).map(s -> s.getDefaultCurrency())
                .orElse(null));
    }

    static ReportBucket bucket(String raw, ResolvedFilter f) {
        if (raw == null || raw.isBlank()) return ReportBuckets.auto(f.from(), f.to());
        try {
            return ReportBucket.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ReportErrors.invalid("bucket", "must be DAY, WEEK or MONTH");
        }
    }
}
