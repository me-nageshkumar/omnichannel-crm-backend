package me.nageshk.omnichannel.crm.report;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.common.PicklistItem;
import me.nageshk.omnichannel.crm.common.TargetType;
import me.nageshk.omnichannel.crm.field.CrmFieldDefinition;
import me.nageshk.omnichannel.crm.field.FieldDefinitionService;
import me.nageshk.omnichannel.crm.field.FieldOption;
import me.nageshk.omnichannel.crm.pipeline.CrmPipeline;
import me.nageshk.omnichannel.crm.pipeline.CrmPipelineService;
import me.nageshk.omnichannel.crm.pipeline.CrmStage;
import me.nageshk.omnichannel.crm.platform.NumberDirectory;
import me.nageshk.omnichannel.crm.platform.PeopleDirectory;
import me.nageshk.omnichannel.crm.platform.PlatformNumber;
import me.nageshk.omnichannel.crm.platform.PlatformPerson;
import me.nageshk.omnichannel.crm.report.dto.ActivitySummaryView;
import me.nageshk.omnichannel.crm.report.dto.ByNumberView;
import me.nageshk.omnichannel.crm.report.dto.ByOwnerView;
import me.nageshk.omnichannel.crm.report.dto.BySourceView;
import me.nageshk.omnichannel.crm.report.dto.DealsOverTimeRequest;
import me.nageshk.omnichannel.crm.report.dto.DealsOverTimeView;
import me.nageshk.omnichannel.crm.report.dto.GridRequest;
import me.nageshk.omnichannel.crm.report.dto.GridView;
import me.nageshk.omnichannel.crm.report.dto.LifecycleFunnelView;
import me.nageshk.omnichannel.crm.report.dto.PipelineSummaryView;
import me.nageshk.omnichannel.crm.report.dto.StageDurationView;
import me.nageshk.omnichannel.crm.settings.CrmSettings;
import me.nageshk.omnichannel.crm.settings.CrmSettingsService;
import org.springframework.stereotype.Service;

/** The reports (spec phase C): resolves the filter and the brand's defaults, then delegates to one report each. */
@Service
public class CrmReportService {

    private final ReportFilterResolver resolver;
    private final ReportScope scope;
    private final CrmSettingsService settings;
    private final PeopleDirectory people;
    private final NumberDirectory numbers;
    private final PipelineSummaryReport pipelineSummary;
    private final DealsOverTimeReport dealsOverTime;
    private final BreakdownReports breakdowns;
    private final LifecycleFunnelReport lifecycleFunnel;
    private final ActivitySummaryReport activitySummary;
    private final StageDurationReport stageDuration;
    private final GridReport grid;
    private final FieldDefinitionService fields;
    private final CrmPipelineService pipelines;

    CrmReportService(ReportFilterResolver resolver, ReportScope scope, CrmSettingsService settings,
            PeopleDirectory people, NumberDirectory numbers, PipelineSummaryReport pipelineSummary,
            DealsOverTimeReport dealsOverTime, BreakdownReports breakdowns, LifecycleFunnelReport lifecycleFunnel,
            ActivitySummaryReport activitySummary, StageDurationReport stageDuration, GridReport grid,
            FieldDefinitionService fields, CrmPipelineService pipelines) {
        this.resolver = resolver;
        this.scope = scope;
        this.settings = settings;
        this.people = people;
        this.numbers = numbers;
        this.pipelineSummary = pipelineSummary;
        this.dealsOverTime = dealsOverTime;
        this.breakdowns = breakdowns;
        this.lifecycleFunnel = lifecycleFunnel;
        this.activitySummary = activitySummary;
        this.stageDuration = stageDuration;
        this.grid = grid;
        this.fields = fields;
        this.pipelines = pipelines;
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

    public ByOwnerView byOwner(CrmContext ctx, ReportFilter filter) {
        ResolvedFilter f = resolver.resolve(ctx, filter);
        ByOwnerView raw = breakdowns.byOwner(ctx, f, currency(ctx), id -> id);
        Set<String> ids = raw.rows().stream().map(ByOwnerView.Row::ownerId).filter(id -> !id.isEmpty())
                .collect(Collectors.toSet());
        Function<String, String> names = personNames(ids);
        return new ByOwnerView(raw.currency(), raw.rows().stream().map(r -> r.ownerId().isEmpty() ? r
                : new ByOwnerView.Row(r.ownerId(), names.apply(r.ownerId()), r.openCount(), r.openAmount(),
                        r.wonCount(), r.wonAmount(), r.lostCount(), r.winRate())).toList(), raw.otherCurrencyDeals());
    }

    public BySourceView bySource(CrmContext ctx, ReportFilter filter) {
        ResolvedFilter f = resolver.resolve(ctx, filter);
        Map<String, String> labels = picklistLabels(settings(ctx).getDealSources());
        return breakdowns.bySource(ctx, f, currency(ctx), key -> labels.getOrDefault(key, key));
    }

    public ByNumberView byNumber(CrmContext ctx, ReportFilter filter) {
        ResolvedFilter f = resolver.resolve(ctx, filter);
        Map<String, String> labels = numbers.numbers(ctx.workspaceId()).stream().collect(Collectors.toMap(
                PlatformNumber::id, n -> n.label() == null || n.label().isBlank() ? n.number() : n.label(),
                (a, b) -> a));
        return breakdowns.byNumber(ctx, f, currency(ctx), id -> labels.getOrDefault(id, "Removed number"));
    }

    public LifecycleFunnelView lifecycleFunnel(CrmContext ctx, ReportFilter filter) {
        ResolvedFilter f = resolver.resolve(ctx, filter);
        return lifecycleFunnel.build(ctx, f, settings(ctx).getLifecycleStatuses());
    }

    public ActivitySummaryView activitySummary(CrmContext ctx, ReportFilter filter) {
        ResolvedFilter f = resolver.resolve(ctx, filter);
        ActivitySummaryView raw = activitySummary.build(ctx, f, id -> id);
        Function<String, String> names = personNames(raw.rows().stream().map(ActivitySummaryView.Row::actorId)
                .collect(Collectors.toSet()));
        return new ActivitySummaryView(raw.rows().stream().map(r -> new ActivitySummaryView.Row(r.actorId(),
                names.apply(r.actorId()), r.tasksCompleted(), r.notesAdded(), r.stageMoves(), r.dealsWon(),
                r.dealsLost(), r.calls(), r.meetings(), r.emails())).toList());
    }

    public StageDurationView stageDuration(CrmContext ctx, ReportFilter filter) {
        ResolvedFilter f = resolver.resolve(ctx, filter);
        CrmPipeline pipeline = scope.pipelineOrDefault(ctx, f.pipelineId());
        return stageDuration.build(ctx, f, pipeline);
    }

    public GridView grid(CrmContext ctx, GridRequest req) {
        GridRequest r = req == null ? new GridRequest(null, null, null, null, null, null, null, null, null, null,
                null, null) : req;
        ResolvedFilter f = resolver.resolve(ctx, r.filter());
        GridFields.Resolved g = GridFields.resolve(r, f, entity -> fields.list(ctx, entity, false));
        GridView raw = grid.build(ctx, f, g, currency(ctx), key -> key);
        Function<String, String> labels = gridLabels(ctx, g, raw);
        return new GridView(raw.entityType(), raw.groupBy(), raw.metric(), raw.field(), raw.dateBucket(),
                raw.currency(), raw.rows().stream().map(row -> GridView.OTHER.equals(row.key()) ? row
                        : new GridView.Row(row.key(), labels.apply(row.key()), row.bucket(), row.value(), row.count()))
                        .toList(), raw.truncated(), raw.otherCurrencyDeals());
    }

    /** How the grid names its keys (spec C14); "" is always "Not set". */
    private Function<String, String> gridLabels(CrmContext ctx, GridFields.Resolved g, GridView raw) {
        Function<String, String> named = switch (g.labels()) {
            case PIPELINE -> {
                Map<String, String> m = pipelines.list(ctx).stream()
                        .collect(Collectors.toMap(CrmPipeline::getId, CrmPipeline::getName, (a, b) -> a));
                yield id -> m.getOrDefault(id, "Removed pipeline");
            }
            case STAGE -> {
                Map<String, String> m = new java.util.HashMap<>();
                for (CrmPipeline p : pipelines.list(ctx)) for (CrmStage s : p.getStages()) m.put(s.getId(), s.getName());
                yield id -> m.getOrDefault(id, "Removed stage");
            }
            case PERSON -> personNames(raw.rows().stream().map(GridView.Row::key)
                    .filter(k -> !k.isEmpty() && !GridView.OTHER.equals(k)).collect(Collectors.toSet()));
            case DEAL_SOURCES -> labelsOf(settings(ctx).getDealSources());
            case LOST_REASONS -> labelsOf(settings(ctx).getLostReasons());
            case LIFECYCLE -> labelsOf(settings(ctx).getLifecycleStatuses());
            case INDUSTRIES -> labelsOf(settings(ctx).getIndustries());
            case NUMBER -> {
                Map<String, String> m = numbers.numbers(ctx.workspaceId()).stream().collect(Collectors.toMap(
                        PlatformNumber::id, n -> n.label() == null || n.label().isBlank() ? n.number() : n.label(),
                        (a, b) -> a));
                yield id -> m.getOrDefault(id, "Removed number");
            }
            case OPTIONS -> {
                Map<String, String> m = new java.util.HashMap<>();
                CrmFieldDefinition d = g.groupField();
                if (d != null && d.getOptions() != null) for (FieldOption o : d.getOptions()) m.put(o.key(), o.label());
                yield key -> m.getOrDefault(key, key);
            }
            case BOOLEAN -> key -> "true".equals(key) ? "Yes" : "false".equals(key) ? "No" : key;
            default -> key -> key;
        };
        return key -> key.isEmpty() ? "Not set" : named.apply(key);
    }

    private static Function<String, String> labelsOf(List<PicklistItem> items) {
        Map<String, String> m = picklistLabels(items);
        return key -> m.getOrDefault(key, key);
    }

    /** Display names for user ids; one lookup, "Unknown person" for ids the brand no longer knows. */
    Function<String, String> personNames(Collection<String> ids) {
        Map<String, PlatformPerson> found = ids.isEmpty() ? Map.of() : people.people(ids);
        return id -> {
            PlatformPerson p = found.get(id);
            return p == null || p.name() == null || p.name().isBlank() ? "Unknown person" : p.name();
        };
    }

    static Map<String, String> picklistLabels(List<PicklistItem> items) {
        Map<String, String> out = new java.util.HashMap<>();
        if (items != null) {
            for (PicklistItem i : items) {
                if (i.key() != null) out.put(i.key(), i.label() == null || i.label().isBlank() ? i.key() : i.label());
            }
        }
        return out;
    }

    CrmSettings settings(CrmContext ctx) {
        return ctx.settings() != null ? ctx.settings() : settings.find(ctx.workspaceId()).orElseGet(CrmSettings::new);
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
