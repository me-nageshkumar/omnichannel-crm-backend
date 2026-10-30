package me.nageshk.omnichannel.crm.report;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.pipeline.CrmPipeline;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * The first {@code $match} of every report (spec C2): the brand, the caller's access and the narrowing filters.
 * Date ranges are added by each report for the field it reports on, except activities, which always range on
 * {@code happenedAt}.
 */
@Component
public class ReportScope {

    private final MongoTemplate mongo;

    public ReportScope(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /** Deals the caller may see, narrowed by pipeline, number and owner. */
    public Criteria deals(CrmContext ctx, ResolvedFilter f) {
        Criteria c = scoped(ctx, f, "pipelineId");
        return narrow(c, f, "channel.phoneNumberId", "ownerId");
    }

    /** Contacts the caller may see (any of their pipelines accessible), narrowed by pipeline, number and owner. */
    public Criteria contacts(CrmContext ctx, ResolvedFilter f) {
        Criteria c = scoped(ctx, f, "pipelineIds");
        return narrow(c, f, "channel.phoneNumberId", "ownerId");
    }

    /** Companies are brand-wide; only the owner filter narrows them. */
    public Criteria companies(CrmContext ctx, ResolvedFilter f) {
        Criteria c = Criteria.where("workspaceId").is(ctx.workspaceId());
        return f.ownerIds().isEmpty() ? c : c.and("ownerId").in(f.ownerIds());
    }

    /**
     * Activities in the range the caller may count: for restricted callers only entries stamped with an accessible
     * pipeline — unlike the timeline, entries without one are excluded, as a report would count other teams' work.
     */
    public Criteria activities(CrmContext ctx, ResolvedFilter f) {
        Criteria c = scoped(ctx, f, "pipelineId");
        c = narrow(c, f, "phoneNumberId", "actorId");
        return c.and("happenedAt").gte(f.from()).lt(f.to());
    }

    /** {@code field} in [from, to). */
    public static Criteria inRange(String field, ResolvedFilter f) {
        return Criteria.where(field).gte(f.from()).lt(f.to());
    }

    /**
     * The pipeline a stage-based report shows: the named one (already checked accessible by the resolver), else the
     * brand's default when accessible, else the oldest accessible one; 400 when there is none.
     */
    public CrmPipeline pipelineOrDefault(CrmContext ctx, String pipelineId) {
        List<CrmPipeline> all = mongo.find(new Query(Criteria.where("workspaceId").is(ctx.workspaceId())),
                CrmPipeline.class);
        if (pipelineId != null) {
            return all.stream().filter(p -> p.getId().equals(pipelineId) && ctx.pipelines().allows(p.getId()))
                    .findFirst().orElseThrow(() -> ReportErrors.invalid("pipelineId", "is not a pipeline you can see"));
        }
        List<CrmPipeline> accessible = all.stream().filter(p -> ctx.pipelines().allows(p.getId())).toList();
        return accessible.stream().filter(p -> Boolean.TRUE.equals(p.getIsDefault())).findFirst()
                .or(() -> accessible.stream().min(Comparator.comparing(CrmPipeline::getCreationTime,
                        Comparator.nullsLast(Comparator.naturalOrder()))))
                .orElseThrow(() -> ReportErrors.invalid("pipelineId", "No accessible pipeline"));
    }

    /**
     * Brand + the filter's pipeline when one is named (the resolver has already checked it is accessible), else the
     * caller's whole access on {@code pipelineField}; an empty access set matches nothing.
     */
    private static Criteria scoped(CrmContext ctx, ResolvedFilter f, String pipelineField) {
        Criteria c = Criteria.where("workspaceId").is(ctx.workspaceId());
        if (f.pipelineId() != null) return c.and(pipelineField).is(f.pipelineId());
        if (!ctx.pipelines().all()) {
            Set<String> ids = ctx.pipelines().pipelineIds();
            c = c.and(pipelineField).in(ids.isEmpty() ? List.of("") : ids);
        }
        return c;
    }

    private static Criteria narrow(Criteria c, ResolvedFilter f, String numberField, String ownerField) {
        if (!f.phoneNumberIds().isEmpty()) c = c.and(numberField).in(f.phoneNumberIds());
        if (!f.ownerIds().isEmpty()) c = c.and(ownerField).in(f.ownerIds());
        return c;
    }
}
