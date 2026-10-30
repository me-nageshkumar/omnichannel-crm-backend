package me.nageshk.omnichannel.crm.report;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.crm.common.TargetType;
import me.nageshk.omnichannel.crm.field.CrmFieldDefinition;
import me.nageshk.omnichannel.crm.field.FieldType;
import me.nageshk.omnichannel.crm.report.dto.GridRequest;

/** The grid's allowlist (spec C14): no request string reaches the database except through it. */
public final class GridFields {

    public static final String CUSTOM_PREFIX = "custom:";
    static final int MAX_GROUPS = 100;
    private static final Set<String> METRICS = Set.of("COUNT", "SUM", "AVG");
    private static final Set<FieldType> GROUPABLE = Set.of(FieldType.SELECT, FieldType.BOOLEAN, FieldType.USER,
            FieldType.TEXT);
    private static final Set<FieldType> NUMERIC = Set.of(FieldType.NUMBER, FieldType.CURRENCY);

    /** Where a built-in group's label comes from. */
    public enum Labels { PLAIN, PIPELINE, STAGE, PERSON, DEAL_SOURCES, NUMBER, LOST_REASONS, LIFECYCLE, INDUSTRIES,
        ORIGIN, STATUS, OPTIONS, BOOLEAN }

    /** The checked request: the collection, the paths to group and sum on, and how to label the keys. */
    public record Resolved(TargetType entity, String collection, String groupPath, Labels labels,
            CrmFieldDefinition groupField, String metric, String valuePath, String dateField, ReportBucket bucket,
            boolean money) {}

    private record Builtin(String path, Labels labels) {}

    private static final Map<TargetType, Map<String, Builtin>> BUILTINS = Map.of(
            TargetType.DEAL, Map.ofEntries(
                    Map.entry("pipeline", new Builtin("pipelineId", Labels.PIPELINE)),
                    Map.entry("stage", new Builtin("stageId", Labels.STAGE)),
                    Map.entry("status", new Builtin("status", Labels.STATUS)),
                    Map.entry("owner", new Builtin("ownerId", Labels.PERSON)),
                    Map.entry("source", new Builtin("source", Labels.DEAL_SOURCES)),
                    Map.entry("number", new Builtin("channel.phoneNumberId", Labels.NUMBER)),
                    Map.entry("currency", new Builtin("currency", Labels.PLAIN)),
                    Map.entry("lostReason", new Builtin("lostReason", Labels.LOST_REASONS))),
            TargetType.CONTACT, Map.of(
                    "lifecycle", new Builtin("lifecycleStatus", Labels.LIFECYCLE),
                    "owner", new Builtin("ownerId", Labels.PERSON),
                    "source", new Builtin("source", Labels.DEAL_SOURCES),
                    "origin", new Builtin("origin.kind", Labels.ORIGIN),
                    "number", new Builtin("channel.phoneNumberId", Labels.NUMBER)),
            TargetType.COMPANY, Map.of(
                    "industry", new Builtin("industry", Labels.INDUSTRIES),
                    "size", new Builtin("size", Labels.PLAIN),
                    "owner", new Builtin("ownerId", Labels.PERSON)));

    private static final Map<TargetType, String> COLLECTIONS = Map.of(TargetType.DEAL, "crmDeals",
            TargetType.CONTACT, "crmContacts", TargetType.COMPANY, "crmCompanies");

    private GridFields() {}

    /** @param active the entity's active field definitions (looked up only when the request names one) */
    public static Resolved resolve(GridRequest r, ResolvedFilter f, java.util.function.Function<TargetType,
            List<CrmFieldDefinition>> active) {
        TargetType entity = entity(r.entityType());
        String collection = COLLECTIONS.get(entity);
        String g = r.groupBy() == null ? "" : r.groupBy().trim();
        if (g.isEmpty()) throw ReportErrors.invalid("groupBy", "is required");
        String groupPath;
        Labels labels;
        CrmFieldDefinition groupField = null;
        if (g.startsWith(CUSTOM_PREFIX)) {
            groupField = custom(entity, g, active.apply(entity), GROUPABLE, "groupBy");
            groupPath = "customFields." + groupField.getKey();
            labels = groupField.getType() == FieldType.SELECT ? Labels.OPTIONS
                    : groupField.getType() == FieldType.BOOLEAN ? Labels.BOOLEAN
                    : groupField.getType() == FieldType.USER ? Labels.PERSON : Labels.PLAIN;
        } else {
            Builtin b = BUILTINS.get(entity).get(g);
            if (b == null) throw ReportErrors.invalid("groupBy", "is not a group of " + entity);
            if ("stage".equals(g) && f.pipelineId() == null) {
                throw ReportErrors.invalid("pipelineId", "grouping by stage needs a pipeline");
            }
            groupPath = b.path();
            labels = b.labels();
        }
        String metric = r.metric() == null || r.metric().isBlank() ? "COUNT"
                : r.metric().trim().toUpperCase(Locale.ROOT);
        if (!METRICS.contains(metric)) throw ReportErrors.invalid("metric", "must be COUNT, SUM or AVG");
        String valuePath = null;
        boolean money = false;
        if (!"COUNT".equals(metric)) {
            String fld = r.field() == null ? "" : r.field().trim();
            if ("amount".equals(fld) && entity == TargetType.DEAL) {
                valuePath = "amount";
                money = true;
            } else if (fld.startsWith(CUSTOM_PREFIX)) {
                CrmFieldDefinition v = custom(entity, fld, active.apply(entity), NUMERIC, "field");
                valuePath = "customFields." + v.getKey();
                money = v.getType() == FieldType.CURRENCY && entity == TargetType.DEAL;
            } else {
                throw ReportErrors.invalid("field", "SUM and AVG need amount (deals) or a number or currency field");
            }
        }
        String date = r.dateField() == null || r.dateField().isBlank() ? "creationTime" : r.dateField().trim();
        if (!"creationTime".equals(date) && !("closedAt".equals(date) && entity == TargetType.DEAL)) {
            throw ReportErrors.invalid("dateField", "must be creationTime, or closedAt for deals");
        }
        ReportBucket bucket = null;
        if (r.dateBucket() != null && !r.dateBucket().isBlank()) {
            try {
                bucket = ReportBucket.valueOf(r.dateBucket().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw ReportErrors.invalid("dateBucket", "must be DAY, WEEK or MONTH");
            }
        }
        return new Resolved(entity, collection, groupPath, labels, groupField, metric, valuePath, date, bucket, money);
    }

    private static TargetType entity(String raw) {
        String e = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!COLLECTIONS.containsKey(e.isEmpty() ? TargetType.PIPELINE : safe(e))) {
            throw ReportErrors.invalid("entityType", "must be DEAL, CONTACT or COMPANY");
        }
        return TargetType.valueOf(e);
    }

    private static TargetType safe(String e) {
        try {
            return TargetType.valueOf(e);
        } catch (IllegalArgumentException x) {
            return TargetType.PIPELINE;
        }
    }

    private static CrmFieldDefinition custom(TargetType entity, String spec, List<CrmFieldDefinition> defs,
            Set<FieldType> allowed, String param) {
        String key = spec.substring(CUSTOM_PREFIX.length()).trim();
        return defs.stream().filter(d -> d.isActive() && d.getEntityType() == entity && key.equals(d.getKey()))
                .filter(d -> allowed.contains(d.getType())).findFirst()
                .orElseThrow(() -> ReportErrors.invalid(param, "unknown or unsupported custom field: " + key));
    }
}
