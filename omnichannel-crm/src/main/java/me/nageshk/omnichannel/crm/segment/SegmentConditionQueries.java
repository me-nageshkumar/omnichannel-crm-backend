package me.nageshk.omnichannel.crm.segment;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.common.TimeLimited;
import me.nageshk.omnichannel.crm.contact.ChannelAccounts;
import me.nageshk.omnichannel.crm.field.FieldType;
import me.nageshk.omnichannel.crm.platform.ChannelContactQuery;
import me.nageshk.omnichannel.crm.platform.ChannelRule;
import me.nageshk.omnichannel.crm.segment.SegmentFields.FieldSpec;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * One Mongo query per segment condition (spec D C5, C16, C22), every one scoped to the caller: contact rules on
 * {@code crmContacts} inside the universe, the deal rules of one AND group as one query on {@code crmDeals} (one deal
 * meets them all), channel rules through {@link ChannelContactQuery} then mapped to CRM contacts. Every read runs
 * under the 10 s limit; any set over {@code maxSet} ids is refused as too broad.
 */
@Component
public class SegmentConditionQueries {

    public static final int MAX_SET = 200_000;
    static final int CHUNK = 10_000;

    private final MongoTemplate mongo;
    private final ChannelContactQuery channel;
    private final Clock clock;
    private final int maxSet;

    @Autowired
    public SegmentConditionQueries(MongoTemplate mongo, ChannelContactQuery channel, Clock clock) {
        this(mongo, channel, clock, MAX_SET);
    }

    /** {@code maxSet} is the too-broad cap; tests lower it. */
    public SegmentConditionQueries(MongoTemplate mongo, ChannelContactQuery channel, Clock clock, int maxSet) {
        this.mongo = mongo;
        this.channel = channel;
        this.clock = clock;
        this.maxSet = maxSet;
    }

    /** The caller's universe: the brand's CRM contacts whose pipelines meet the caller's access. */
    public Criteria universe(CrmContext ctx) {
        Criteria c = Criteria.where("workspaceId").is(ctx.workspaceId());
        return ctx.pipelines().all() ? c : c.andOperator(ctx.pipelines().criteria("pipelineIds"));
    }

    /** The brand's deals the caller may see. */
    public Criteria visibleDeals(CrmContext ctx) {
        Criteria c = Criteria.where("workspaceId").is(ctx.workspaceId());
        return ctx.pipelines().all() ? c : c.andOperator(ctx.pipelines().criteria("pipelineId"));
    }

    public Set<String> universeIds(CrmContext ctx, Criteria universe) {
        return ids("crmContacts", universe);
    }

    public Set<String> contactRule(CrmContext ctx, Criteria universe, SegmentRule r, FieldSpec spec, ZoneId zone) {
        Criteria rule = criteria(contactPath(spec), r, spec, new SegmentDates(clock, zone));
        return ids("crmContacts", new Criteria().andOperator(universe, rule));
    }

    /** The contacts (primary or linked) of a visible deal meeting every rule; negatives apply to the deal itself. */
    public Set<String> dealGroup(CrmContext ctx, List<SegmentRule> dealRules, Map<String, FieldSpec> specs, ZoneId zone) {
        SegmentDates dates = new SegmentDates(clock, zone);
        List<Criteria> all = new ArrayList<>();
        all.add(visibleDeals(ctx));
        for (SegmentRule r : dealRules) {
            FieldSpec spec = specs.get(r.field());
            all.add(criteria(dealPath(spec), r, spec, dates));
        }
        Query q = new Query(new Criteria().andOperator(all)).maxTime(TimeLimited.MAX_TIME);
        q.fields().include("primaryCrmContactId").include("crmContactIds");
        Set<String> out = new HashSet<>();
        for (Document d : guarded(() -> mongo.find(q, Document.class, "crmDeals"))) {
            String primary = d.getString("primaryCrmContactId");
            if (primary != null) out.add(primary);
            List<String> linked = d.getList("crmContactIds", String.class);
            if (linked != null) out.addAll(linked);
            if (out.size() > maxSet) throw SegmentErrors.tooBroad();
        }
        return out;
    }

    /**
     * The universe's CRM contacts whose host contact matches the channel rule (dates already absolute). A negative
     * operator is asked of the port in its positive form and complemented within the universe here, so a CRM contact
     * whose host contact lacks the field counts as "not" having it.
     */
    public Set<String> channelRule(CrmContext ctx, Criteria universe, SegmentRule r, ZoneId zone) {
        SegmentDates dates = new SegmentDates(clock, zone);
        String field = r.field().substring(SegmentFields.CHANNEL.length() + 1);
        ChannelRule hostRule = new ChannelRule(field, hostOperator(hostDateOperator(r.operator())),
                hostValue(field, r, dates));
        Set<String> hostIds = guarded(() -> channel.matching(ctx.workspaceId(), hostRule, maxSet));
        if (hostIds.size() > maxSet) throw SegmentErrors.tooBroad();
        Set<String> matched = new HashSet<>();
        List<String> list = new ArrayList<>(hostIds);
        for (int from = 0; from < list.size(); from += CHUNK) {
            List<String> chunk = list.subList(from, Math.min(list.size(), from + CHUNK));
            matched.addAll(ids("crmContacts", new Criteria().andOperator(universe, Criteria.where("contactId").in(chunk))));
            if (matched.size() > maxSet) throw SegmentErrors.tooBroad();
        }
        return SegmentFields.isNegative(r.operator()) ? SegmentSets.complement(universeIds(ctx, universe), matched)
                : matched;
    }

    /** A negative channel operator is resolved as a complement: the port is asked for the positive match. */
    static String hostOperator(String op) {
        return switch (op) {
            case "hasNot" -> "has";
            case "hasNone" -> "hasAny";
            case "isNot" -> "is";
            case "notIn" -> "in";
            case "notContains" -> "contains";
            case "isNotSet" -> "isSet";
            case "isEmpty" -> "isNotEmpty";
            default -> op;
        };
    }

    /** Dates go to the port as instants: zone days and relative day counts are resolved here. */
    private static Object hostValue(String field, SegmentRule r, SegmentDates dates) {
        if (!"lastMessageAt".equals(field)) return r.value();
        return switch (r.operator()) {
            case "before", "after" -> dates.startOfDay((String) r.value()).toString();
            case "between" -> {
                List<?> l = (List<?>) r.value();
                yield List.of(dates.startOfDay((String) l.get(0)).toString(), dates.startOfDay((String) l.get(1)).toString());
            }
            // "in the last n days" = on or after the day n days ago: the port's `after d` means from the day after d
            case "withinLastDays" -> dates.startOfDay(dates.dayAgo(SegmentDates.days(r.value()) + 1)).toString();
            // "more than n days ago" = before the day n days ago
            case "moreThanDaysAgo" -> dates.startOfDay(dates.dayAgo(SegmentDates.days(r.value()))).toString();
            default -> r.value();
        };
    }

    /** The channel operator the port sees for a relative-day rule. */
    static String hostDateOperator(String op) {
        return switch (op) {
            case "withinLastDays" -> "after";
            case "moreThanDaysAgo" -> "before";
            default -> op;
        };
    }

    static String contactPath(FieldSpec spec) {
        String key = spec.key();
        if (key.startsWith(SegmentFields.CONTACT_CUSTOM)) return "customFields." + key.substring(SegmentFields.CONTACT_CUSTOM.length());
        if ("contact.channelAccountId".equals(key)) return ChannelAccounts.FIELD;
        return key.substring(SegmentFields.CONTACT.length() + 1);
    }

    static String dealPath(FieldSpec spec) {
        String key = spec.key();
        if (key.startsWith(SegmentFields.DEAL_CUSTOM)) return "customFields." + key.substring(SegmentFields.DEAL_CUSTOM.length());
        if ("deal.daysInStage".equals(key)) return "stageEnteredAt";
        return key.substring(SegmentFields.DEAL.length() + 1);
    }

    /** The Mongo criteria of one CRM-side rule. */
    static Criteria criteria(String path, SegmentRule r, FieldSpec spec, SegmentDates dates) {
        String op = r.operator();
        Object v = r.value();
        if ("deal.daysInStage".equals(spec.key())) return daysInStage(path, op, v, dates);
        if (spec.type() == SegmentValueType.DATE) {
            if (spec.fieldType() == FieldType.DATE || "deal.expectedCloseDate".equals(spec.key())) {
                return dates.dayStringCriteria(path, op, v);
            }
            if (spec.fieldType() == FieldType.DATETIME) return dates.instantStringCriteria(path, op, v);
            return dates.dateCriteria(path, op, v);
        }
        Criteria c = Criteria.where(path);
        if (spec.type() == SegmentValueType.TEXT) return text(c, op, v);
        return switch (op) {
            case "is", "eq", "has", "contains" -> c.is(scalar(spec, v));
            case "isNot", "neq", "hasNot" -> c.ne(scalar(spec, v));
            case "in", "hasAny" -> c.in(list(spec, v));
            case "notIn", "hasNone" -> c.nin(list(spec, v));
            case "hasAll" -> c.all(list(spec, v));
            case "lt" -> c.lt(scalar(spec, v));
            case "lte" -> c.lte(scalar(spec, v));
            case "gt" -> c.gt(scalar(spec, v));
            case "gte" -> c.gte(scalar(spec, v));
            case "between" -> {
                List<?> l = (List<?>) v;
                yield c.gte(scalar(spec, l.get(0))).lte(scalar(spec, l.get(1)));
            }
            case "notContains" -> c.not().regex(Pattern.quote(((String) v).trim()), "i");
            case "startsWith" -> c.regex("^" + Pattern.quote(((String) v).trim()), "i");
            case "isSet" -> c.ne(null);
            case "isNotSet" -> c.is(null);
            case "isEmpty" -> new Criteria().orOperator(Criteria.where(path).is(null), Criteria.where(path).size(0));
            case "isNotEmpty" -> Criteria.where(path + ".0").exists(true);
            default -> throw new IllegalArgumentException("not a segment operator: " + op);
        };
    }

    /**
     * TEXT rules match case-insensitively with the value quoted, like the host's contact filters: {@code is} is the
     * whole value, {@code contains} any part, {@code startsWith} the beginning; the negatives are their complements.
     */
    private static Criteria text(Criteria c, String op, Object v) {
        String q = v instanceof String s ? Pattern.quote(s.trim()) : "";
        return switch (op) {
            case "is" -> c.regex("^" + q + "$", "i");
            case "isNot" -> c.not().regex("^" + q + "$", "i");
            case "contains" -> c.regex(q, "i");
            case "notContains" -> c.not().regex(q, "i");
            case "startsWith" -> c.regex("^" + q, "i");
            case "isSet" -> c.ne(null);
            case "isNotSet" -> c.is(null);
            default -> throw new IllegalArgumentException("not a text operator: " + op);
        };
    }

    /** Numbers compare as Decimal128; booleans as booleans; everything else as the string it is. */
    private static Object scalar(FieldSpec spec, Object v) {
        String s = v instanceof String str ? str.trim() : null;
        return switch (spec.type()) {
            case NUMBER, MONEY -> new Decimal128(new BigDecimal(s));
            case BOOLEAN -> Boolean.valueOf(s);
            default -> v;
        };
    }

    private static List<Object> list(FieldSpec spec, Object v) {
        List<Object> out = new ArrayList<>();
        for (Object o : (List<?>) v) out.add(scalar(spec, o));
        return out;
    }

    /** "n days in the stage" inverts onto {@code stageEnteredAt}: more days means an earlier instant. */
    private static Criteria daysInStage(String path, String op, Object v, SegmentDates dates) {
        Criteria c = Criteria.where(path);
        return switch (op) {
            case "gt" -> c.lt(dates.daysAgo(SegmentDates.days(v)));
            case "gte" -> c.lte(dates.daysAgo(SegmentDates.days(v)));
            case "lt" -> c.gt(dates.daysAgo(SegmentDates.days(v)));
            case "lte" -> c.gte(dates.daysAgo(SegmentDates.days(v)));
            case "eq" -> c.gt(dates.daysAgo(SegmentDates.days(v) + 1)).lte(dates.daysAgo(SegmentDates.days(v)));
            case "neq" -> new Criteria().orOperator(Criteria.where(path).lte(dates.daysAgo(SegmentDates.days(v) + 1)),
                    Criteria.where(path).gt(dates.daysAgo(SegmentDates.days(v))));
            case "between" -> {
                List<?> l = (List<?>) v;
                yield c.gte(dates.daysAgo(SegmentDates.days(l.get(1)) + 1)).lte(dates.daysAgo(SegmentDates.days(l.get(0))));
            }
            default -> throw new IllegalArgumentException("not a number operator: " + op);
        };
    }

    /** Ids matching the criteria, read in chunks of {@link #CHUNK}; over {@code maxSet} → too broad. */
    private Set<String> ids(String collection, Criteria criteria) {
        Set<String> out = new HashSet<>();
        Object last = null;
        while (true) {
            Criteria page = last == null ? criteria : new Criteria().andOperator(criteria, Criteria.where("_id").gt(last));
            Query q = new Query(page).with(org.springframework.data.domain.Sort.by("_id")).limit(CHUNK)
                    .maxTime(TimeLimited.MAX_TIME);
            q.fields().include("_id");
            List<Document> found = guarded(() -> mongo.find(q, Document.class, collection));
            for (Document d : found) out.add(d.get("_id").toString());
            if (out.size() > maxSet) throw SegmentErrors.tooBroad();
            if (found.size() < CHUNK) return out;
            last = found.get(found.size() - 1).get("_id");
        }
    }

    private static <T> T guarded(Supplier<T> read) {
        return TimeLimited.guard(read, SegmentErrors::timeout);
    }
}
