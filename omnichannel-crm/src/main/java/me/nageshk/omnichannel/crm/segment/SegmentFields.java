package me.nageshk.omnichannel.crm.segment;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import me.nageshk.omnichannel.crm.field.FieldType;

/**
 * The fields a segment rule may use and the operators each value type accepts (spec D C4). Field keys are
 * {@code <group>.<name>}: {@code contact.} is the CRM contact, {@code deal.} a deal of the contact, {@code channel.}
 * the channel contact (labels, attributes, last message, source, do-not-contact). Custom fields are
 * {@code contact.custom:<key>} / {@code deal.custom:<key>}; channel attributes {@code channel.attribute:<id>}.
 */
public final class SegmentFields {

    public static final int MAX_RULES = 30;
    public static final int MAX_DEPTH = 3;
    public static final int MAX_VALUES = 50;
    public static final int MAX_VALUE_LENGTH = 200;
    public static final int MAX_DAYS = 3650;

    public static final String CONTACT = "contact";
    public static final String DEAL = "deal";
    public static final String CHANNEL = "channel";
    public static final String CONTACT_CUSTOM = "contact.custom:";
    public static final String DEAL_CUSTOM = "deal.custom:";
    public static final String CHANNEL_ATTRIBUTE = "channel.attribute:";

    /**
     * A usable field: its key, value type, group and whether it is a brand-defined (custom or attribute) field;
     * {@code fieldType} is the custom field's own type (null for built-ins and attributes), which decides how its
     * values are stored.
     */
    public record FieldSpec(String key, SegmentValueType type, String group, boolean custom, FieldType fieldType) {

        public FieldSpec(String key, SegmentValueType type, String group, boolean custom) {
            this(key, type, group, custom, null);
        }
    }

    /** What the brand defines right now; anything else under a custom prefix is a missing field. */
    public record Known(Set<String> contactCustomKeys, Map<String, FieldType> contactCustomTypes,
            Set<String> dealCustomKeys, Map<String, FieldType> dealCustomTypes, Set<String> attributeIds) {}

    private static final Map<String, SegmentValueType> BUILT_IN = new LinkedHashMap<>();

    static {
        BUILT_IN.put("contact.lifecycleStatus", SegmentValueType.PICK);
        BUILT_IN.put("contact.ownerId", SegmentValueType.USER);
        BUILT_IN.put("contact.source", SegmentValueType.TEXT);
        BUILT_IN.put("contact.origin.kind", SegmentValueType.ENUM);
        BUILT_IN.put("contact.companyId", SegmentValueType.COMPANY);
        BUILT_IN.put("contact.channelAccountId", SegmentValueType.PICK);
        BUILT_IN.put("contact.email", SegmentValueType.TEXT);
        BUILT_IN.put("contact.jobTitle", SegmentValueType.TEXT);
        BUILT_IN.put("contact.creationTime", SegmentValueType.DATE);
        BUILT_IN.put("contact.lastActivityAt", SegmentValueType.DATE);
        BUILT_IN.put("contact.nextTaskDueAt", SegmentValueType.DATE);
        BUILT_IN.put("contact.openDealCount", SegmentValueType.NUMBER);
        BUILT_IN.put("contact.openDealAmount", SegmentValueType.MONEY);
        BUILT_IN.put("deal.pipelineId", SegmentValueType.PICK);
        BUILT_IN.put("deal.stageId", SegmentValueType.PICK);
        BUILT_IN.put("deal.status", SegmentValueType.ENUM);
        BUILT_IN.put("deal.amount", SegmentValueType.MONEY);
        BUILT_IN.put("deal.currency", SegmentValueType.TEXT);
        BUILT_IN.put("deal.ownerId", SegmentValueType.USER);
        BUILT_IN.put("deal.source", SegmentValueType.PICK);
        BUILT_IN.put("deal.lostReason", SegmentValueType.PICK);
        BUILT_IN.put("deal.daysInStage", SegmentValueType.NUMBER);
        BUILT_IN.put("deal.expectedCloseDate", SegmentValueType.DATE);
        BUILT_IN.put("deal.closedAt", SegmentValueType.DATE);
        BUILT_IN.put("deal.creationTime", SegmentValueType.DATE);
        BUILT_IN.put("deal.rotting", SegmentValueType.BOOLEAN);
        BUILT_IN.put("channel.label", SegmentValueType.LABEL);
        BUILT_IN.put("channel.lastMessageAt", SegmentValueType.DATE);
        BUILT_IN.put("channel.source", SegmentValueType.ENUM);
        BUILT_IN.put("channel.doNotContact", SegmentValueType.BOOLEAN);
    }

    /** The built-in keys, in catalogue order. */
    public static final Set<String> BUILT_IN_KEYS = Set.copyOf(BUILT_IN.keySet());

    private static final Map<SegmentValueType, Set<String>> OPERATORS = Map.ofEntries(
            Map.entry(SegmentValueType.TEXT, Set.of("is", "isNot", "contains", "notContains", "startsWith", "isSet", "isNotSet")),
            Map.entry(SegmentValueType.NUMBER, Set.of("eq", "neq", "lt", "lte", "gt", "gte", "between")),
            Map.entry(SegmentValueType.MONEY, Set.of("eq", "neq", "lt", "lte", "gt", "gte", "between")),
            Map.entry(SegmentValueType.DATE, Set.of("before", "after", "between", "withinLastDays", "moreThanDaysAgo", "isSet", "isNotSet")),
            Map.entry(SegmentValueType.PICK, Set.of("is", "isNot", "in", "notIn", "isSet", "isNotSet")),
            Map.entry(SegmentValueType.USER, Set.of("is", "isNot", "in", "notIn", "isSet", "isNotSet")),
            Map.entry(SegmentValueType.ENUM, Set.of("is", "isNot", "in", "notIn", "isSet", "isNotSet")),
            Map.entry(SegmentValueType.COMPANY, Set.of("is", "isNot", "in", "notIn", "isSet", "isNotSet")),
            Map.entry(SegmentValueType.BOOLEAN, Set.of("is")),
            Map.entry(SegmentValueType.LABEL, Set.of("has", "hasNot", "hasAny", "hasAll", "hasNone", "isEmpty", "isNotEmpty")),
            Map.entry(SegmentValueType.MULTI, Set.of("contains", "isSet", "isNotSet")));

    /** Operators whose match is "everything but": resolved as a complement within the caller's universe. */
    private static final Set<String> NEGATIVE = Set.of("isNot", "notIn", "notContains", "neq", "hasNot", "hasNone",
            "isNotSet", "isEmpty");

    /** Operators that take no value. */
    public static final Set<String> NO_VALUE = Set.of("isSet", "isNotSet", "isEmpty", "isNotEmpty");
    /** Operators that take a list. */
    public static final Set<String> LIST_VALUE = Set.of("in", "notIn", "hasAny", "hasAll", "hasNone", "between");
    /** Operators whose value is a day count. */
    public static final Set<String> DAYS_VALUE = Set.of("withinLastDays", "moreThanDaysAgo");

    private SegmentFields() {}

    public static Optional<FieldSpec> spec(String key, Known known) {
        if (key == null) return Optional.empty();
        SegmentValueType builtIn = BUILT_IN.get(key);
        if (builtIn != null) return Optional.of(new FieldSpec(key, builtIn, groupOf(key), false));
        if (key.startsWith(CONTACT_CUSTOM)) {
            String k = key.substring(CONTACT_CUSTOM.length());
            FieldType t = known.contactCustomTypes().get(k);
            return k.isEmpty() || t == null || !known.contactCustomKeys().contains(k) ? Optional.empty()
                    : Optional.of(new FieldSpec(key, typeOfCustom(t), CONTACT, true, t));
        }
        if (key.startsWith(DEAL_CUSTOM)) {
            String k = key.substring(DEAL_CUSTOM.length());
            FieldType t = known.dealCustomTypes().get(k);
            return k.isEmpty() || t == null || !known.dealCustomKeys().contains(k) ? Optional.empty()
                    : Optional.of(new FieldSpec(key, typeOfCustom(t), DEAL, true, t));
        }
        if (key.startsWith(CHANNEL_ATTRIBUTE)) {
            String id = key.substring(CHANNEL_ATTRIBUTE.length());
            return id.isEmpty() || !known.attributeIds().contains(id) ? Optional.empty()
                    : Optional.of(new FieldSpec(key, SegmentValueType.TEXT, CHANNEL, true));
        }
        return Optional.empty();
    }

    /** Whether the key is shaped like a brand-defined field (custom or attribute), known or not. */
    public static boolean isCustomShaped(String key) {
        return key != null && (key.startsWith(CONTACT_CUSTOM) || key.startsWith(DEAL_CUSTOM)
                || key.startsWith(CHANNEL_ATTRIBUTE)) && key.indexOf(':') < key.length() - 1;
    }

    public static String groupOf(String key) {
        int dot = key.indexOf('.');
        return dot < 0 ? key : key.substring(0, dot);
    }

    public static Set<String> operators(SegmentValueType type) {
        return OPERATORS.get(type);
    }

    public static boolean isNegative(String operator) {
        return NEGATIVE.contains(operator);
    }

    /** Whether the rule picks the pipeline or pipelines of a deal, which is what a stage rule needs beside it. */
    public static boolean choosesPipeline(SegmentRule r) {
        return "deal.pipelineId".equals(r.field()) && ("is".equals(r.operator()) || "in".equals(r.operator()));
    }

    public static SegmentValueType typeOfCustom(FieldType type) {
        return switch (type) {
            case TEXT, LONG_TEXT, URL, EMAIL, PHONE -> SegmentValueType.TEXT;
            case NUMBER -> SegmentValueType.NUMBER;
            case CURRENCY -> SegmentValueType.MONEY;
            case BOOLEAN -> SegmentValueType.BOOLEAN;
            case DATE, DATETIME -> SegmentValueType.DATE;
            case SELECT -> SegmentValueType.PICK;
            case MULTI_SELECT -> SegmentValueType.MULTI;
            case USER -> SegmentValueType.USER;
        };
    }
}
