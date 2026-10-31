package me.nageshk.omnichannel.crm.segment;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.common.TargetType;
import me.nageshk.omnichannel.crm.common.TimeLimited;
import me.nageshk.omnichannel.crm.contact.ChannelAccounts;
import me.nageshk.omnichannel.crm.contact.CrmContact;
import me.nageshk.omnichannel.crm.field.CrmFieldDefinition;
import me.nageshk.omnichannel.crm.field.FieldDefinitionService;
import me.nageshk.omnichannel.crm.field.FieldType;
import me.nageshk.omnichannel.crm.pipeline.CrmPipeline;
import me.nageshk.omnichannel.crm.pipeline.CrmPipelineService;
import me.nageshk.omnichannel.crm.pipeline.CrmStage;
import me.nageshk.omnichannel.crm.platform.BrandDirectory;
import me.nageshk.omnichannel.crm.platform.ChannelAccount;
import me.nageshk.omnichannel.crm.platform.ChannelAttribute;
import me.nageshk.omnichannel.crm.platform.ChannelContactQuery;
import me.nageshk.omnichannel.crm.platform.ChannelLabel;
import me.nageshk.omnichannel.crm.segment.SegmentFields.FieldSpec;
import me.nageshk.omnichannel.crm.segment.SegmentFields.Known;
import me.nageshk.omnichannel.crm.segment.SegmentScope.Flag;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Resolves a rule tree for one caller (spec D C5, C8, C16, C20, C22): set algebra over the condition queries, the
 * universe loaded only for NOT groups, flagged rules matching nothing, members loaded in id order and capped.
 */
@Component
public class SegmentResolver {

    private final MongoTemplate mongo;
    private final SegmentConditionQueries queries;
    private final FieldDefinitionService fields;
    private final CrmPipelineService pipelines;
    private final ChannelContactQuery channel;
    private final BrandDirectory brands;

    public SegmentResolver(MongoTemplate mongo, SegmentConditionQueries queries, FieldDefinitionService fields,
            CrmPipelineService pipelines, ChannelContactQuery channel, BrandDirectory brands) {
        this.mongo = mongo;
        this.queries = queries;
        this.fields = fields;
        this.pipelines = pipelines;
        this.channel = channel;
        this.brands = brands;
    }

    /** What the caller may name right now. */
    public SegmentScope scopeFor(CrmContext ctx) {
        Set<String> contactKeys = new HashSet<>();
        Map<String, FieldType> contactTypes = new HashMap<>();
        for (CrmFieldDefinition d : fields.list(ctx, TargetType.CONTACT, false)) {
            contactKeys.add(d.getKey());
            contactTypes.put(d.getKey(), d.getType());
        }
        Set<String> dealKeys = new HashSet<>();
        Map<String, FieldType> dealTypes = new HashMap<>();
        for (CrmFieldDefinition d : fields.list(ctx, TargetType.DEAL, false)) {
            dealKeys.add(d.getKey());
            dealTypes.put(d.getKey(), d.getType());
        }
        Set<String> attributeIds = channel.attributes(ctx.workspaceId()).stream().map(ChannelAttribute::id)
                .collect(Collectors.toSet());
        Known known = new Known(contactKeys, contactTypes, dealKeys, dealTypes, attributeIds);
        Set<String> pipelineIds = new HashSet<>();
        Map<String, Set<String>> stages = new LinkedHashMap<>();
        for (CrmPipeline p : pipelines.list(ctx)) {
            if (!p.isActive()) continue; // as the catalogue: a rule on one is flagged until it is active again
            pipelineIds.add(p.getId());
            stages.put(p.getId(), p.getStages().stream().map(CrmStage::getId).collect(Collectors.toSet()));
        }
        Set<String> labelIds = channel.labels(ctx.workspaceId()).stream().map(ChannelLabel::id).collect(Collectors.toSet());
        Set<String> accountIds = channel.accounts(ctx.workspaceId()).stream().map(ChannelAccount::id).collect(Collectors.toSet());
        return new SegmentScope(known, pipelineIds, stages, labelIds, accountIds, ctx.pipelines().all());
    }

    public SegmentMembers resolve(CrmContext ctx, SegmentRuleGroup rules) {
        return resolve(ctx, rules, scopeFor(ctx));
    }

    public SegmentMembers resolve(CrmContext ctx, SegmentRuleGroup rules, SegmentScope scope) {
        Run run = new Run(ctx, scope, brands.zoneOf(ctx.workspaceId()), queries.universe(ctx), scope.specs(rules));
        Set<String> ids = group(run, rules);
        return members(ctx, ids);
    }

    /** One resolution: the caller, what they may name, the zone, the universe and the specs of the tree's rules. */
    private final class Run {
        final CrmContext ctx;
        final SegmentScope scope;
        final ZoneId zone;
        final Criteria universe;
        final Map<String, FieldSpec> specs;
        private Set<String> universeIds;

        Run(CrmContext ctx, SegmentScope scope, ZoneId zone, Criteria universe, Map<String, FieldSpec> specs) {
            this.ctx = ctx;
            this.scope = scope;
            this.zone = zone;
            this.universe = universe;
            this.specs = specs;
        }

        /** Loaded once per resolution, and only when a NOT group needs a complement. */
        Set<String> universeIds() {
            if (universeIds == null) universeIds = queries.universeIds(ctx, universe);
            return universeIds;
        }
    }

    private Set<String> group(Run run, SegmentRuleGroup g) {
        boolean and = "and".equals(g.combinator());
        List<Set<String>> parts = new ArrayList<>();
        List<SegmentRule> dealRules = new ArrayList<>();
        boolean deadDealGroup = false;
        for (SegmentRuleNode n : g.rules()) {
            Set<String> part;
            if (n instanceof SegmentRuleGroup child) {
                part = group(run, child);
            } else if (n instanceof SegmentRule r) {
                Flag flag = run.scope.flag(r, g);
                boolean deal = SegmentFields.groupOf(r.field()).equals(SegmentFields.DEAL);
                if (flag != Flag.NONE) {
                    if (deal && and) { deadDealGroup = true; continue; }
                    part = Set.of();
                } else if (deal) {
                    if (and) { dealRules.add(r); continue; }
                    part = queries.dealGroup(run.ctx, List.of(r), run.specs, run.zone);
                } else if (SegmentFields.groupOf(r.field()).equals(SegmentFields.CHANNEL)) {
                    part = queries.channelRule(run.ctx, run.universe, r, run.zone);
                } else {
                    part = queries.contactRule(run.ctx, run.universe, r, run.specs.get(r.field()), run.zone);
                }
            } else {
                continue;
            }
            parts.add(part);
            if (and && part.isEmpty() && !g.not()) return Set.of();
        }
        if (and && (deadDealGroup || !dealRules.isEmpty())) {
            parts.add(deadDealGroup ? Set.of() : queries.dealGroup(run.ctx, dealRules, run.specs, run.zone));
        }
        Set<String> result = and ? SegmentSets.intersectAll(parts) : SegmentSets.unionAll(parts);
        return g.not() ? SegmentSets.complement(run.universeIds(), result) : result;
    }

    /** The members in id order, capped; {@code people} counts the distinct host contacts among them. */
    private SegmentMembers members(CrmContext ctx, Set<String> ids) {
        List<String> sorted = new ArrayList<>(ids);
        sorted.sort(null);
        boolean truncated = sorted.size() > SegmentMembers.MAX_MEMBERS;
        List<String> take = truncated ? sorted.subList(0, SegmentMembers.MAX_MEMBERS) : sorted;
        List<SegmentMember> members = new ArrayList<>(take.size());
        Set<String> people = new HashSet<>();
        for (int from = 0; from < take.size(); from += SegmentConditionQueries.CHUNK) {
            List<String> chunk = take.subList(from, Math.min(take.size(), from + SegmentConditionQueries.CHUNK));
            Query q = new Query(Criteria.where("workspaceId").is(ctx.workspaceId()).and("_id").in(chunk))
                    .with(Sort.by("_id")).maxTime(TimeLimited.MAX_TIME);
            q.fields().include("contactId").include(ChannelAccounts.FIELD);
            List<CrmContact> found = TimeLimited.guard(() -> mongo.find(q, CrmContact.class), SegmentErrors::timeout);
            for (CrmContact c : found) {
                members.add(new SegmentMember(c.getId(), c.getContactId(), ChannelAccounts.of(c)));
                if (c.getContactId() != null) people.add(c.getContactId());
            }
        }
        return new SegmentMembers(members, sorted.size(), people.size(), truncated);
    }
}
