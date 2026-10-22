package me.nageshk.omnichannel.contact.advanced;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import me.nageshk.omnichannel.contact.ContactQuery;
import org.bson.Document;

/**
 * Compiles a rule tree into a Mongo query that uses only $and, $or, $nor, $eq, $ne, $in, $nin, $regex, $options,
 * $exists, $gte, $lt and $size.
 */
public final class AdvancedFilterCompiler {

    private AdvancedFilterCompiler() {}

    /** @throws me.nageshk.omnichannel.api.ValidationException if the tree is invalid */
    public static Document compile(RuleGroup rules, Resolved resolved) {
        AdvancedFilterValidator.validate(rules, resolved);
        return group(rules, resolved);
    }

    private static Document group(RuleGroup g, Resolved resolved) {
        List<Document> children = new ArrayList<>();
        for (RuleNode node : g.rules()) {
            children.add(switch (node) {
                case RuleGroup child -> group(child, resolved);
                case Rule child -> rule(child, resolved);
            });
        }
        Document combined = new Document("and".equals(g.combinator()) ? "$and" : "$or", children);
        return g.not() ? new Document("$nor", List.of(combined)) : combined;
    }

    private static Document rule(Rule r, Resolved resolved) {
        return switch (FilterFields.typeOf(r.field()).orElseThrow()) {
            case TEXT -> text(r);
            case ENUM -> enumRule(r);
            case BOOLEAN -> booleanRule(r);
            case DATE -> dateRule(r, resolved);
            case LABELS -> labels(r);
        };
    }

    private static Document text(Rule r) {
        String f = r.field();
        return switch (r.operator()) {
            case "isSet" -> new Document(f, new Document("$exists", true).append("$nin", java.util.Arrays.asList(null, "")));
            case "isNotSet" -> new Document("$or", List.of(
                    new Document(f, new Document("$exists", false)),
                    new Document(f, null),
                    new Document(f, "")));
            case "is" -> regex(f, "^" + pattern(r) + "$");
            case "isNot" -> nor(regex(f, "^" + pattern(r) + "$"));
            case "contains" -> regex(f, pattern(r));
            case "notContains" -> nor(regex(f, pattern(r)));
            case "startsWith" -> regex(f, "^" + pattern(r));
            case "endsWith" -> regex(f, pattern(r) + "$");
            default -> throw new IllegalStateException(r.operator());
        };
    }

    /** Phone values keep only their ASCII digits; other text is stripped; both are escaped. */
    private static String pattern(Rule r) {
        String value = (String) r.value();
        String v = "phone".equals(r.field()) ? value.replaceAll("[^0-9]", "") : value.strip();
        return ContactQuery.escape(v);
    }

    private static Document regex(String field, String pattern) {
        return new Document(field, new Document("$regex", pattern).append("$options", "i"));
    }

    private static Document nor(Document clause) {
        return new Document("$nor", List.of(clause));
    }

    private static Document enumRule(Rule r) {
        Object v = r.value();
        return switch (r.operator()) {
            case "is" -> new Document("source", new Document("$eq", v));
            case "isNot" -> new Document("source", new Document("$ne", v));
            case "in" -> new Document("source", new Document("$in", v));
            case "notIn" -> new Document("source", new Document("$nin", v));
            default -> throw new IllegalStateException(r.operator());
        };
    }

    private static Document booleanRule(Rule r) {
        boolean yes = "true".equals(r.value());
        if ("numberHidden".equals(r.field())) {
            return new Document("phone", yes ? null : new Document("$ne", null));
        }
        return new Document("doNotContact", yes ? new Document("$ne", null) : null);
    }

    private static Document dateRule(Rule r, Resolved resolved) {
        String f = r.field();
        return switch (r.operator()) {
            case "before" -> new Document(f, new Document("$lt", start(r.value(), 0, resolved)));
            case "after" -> new Document(f, new Document("$gte", start(r.value(), 1, resolved)));
            case "between" -> {
                List<?> range = (List<?>) r.value();
                yield new Document(f, new Document("$gte", start(range.get(0), 0, resolved))
                        .append("$lt", start(range.get(1), 1, resolved)));
            }
            case "isSet" -> new Document(f, new Document("$ne", null));
            case "isNotSet" -> new Document(f, null);
            default -> throw new IllegalStateException(r.operator());
        };
    }

    private static Date start(Object date, int plusDays, Resolved resolved) {
        return Date.from(LocalDate.parse((String) date).plusDays(plusDays).atStartOfDay(resolved.zone()).toInstant());
    }

    private static Document labels(Rule r) {
        Object v = r.value();
        return switch (r.operator()) {
            case "has" -> new Document("labels", v);
            case "hasNot" -> new Document("labels", new Document("$ne", v));
            case "hasAny" -> new Document("labels", new Document("$in", v));
            case "hasAll" -> new Document("$and", ((List<?>) v).stream().map(id -> new Document("labels", id)).toList());
            case "hasNone" -> new Document("labels", new Document("$nin", v));
            case "isEmpty" -> new Document("$or", List.of(
                    new Document("labels", new Document("$exists", false)),
                    new Document("labels", new Document("$size", 0))));
            case "isNotEmpty" -> new Document("labels.0", new Document("$exists", true));
            default -> throw new IllegalStateException(r.operator());
        };
    }
}
