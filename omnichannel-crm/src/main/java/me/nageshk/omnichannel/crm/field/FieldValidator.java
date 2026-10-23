package me.nageshk.omnichannel.crm.field;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import me.nageshk.omnichannel.common.api.ValidationException;

/**
 * Checks a record's {@code customFields} against the brand's definitions and returns the map to store. Values are
 * stored JSON-native: text-like types as strings, MULTI_SELECT as a list of keys, BOOLEAN as a boolean, NUMBER and
 * CURRENCY as numbers (a {@code Long} when integral, else a {@code Double}) and DATETIME as an ISO-8601 UTC instant.
 * A key absent from the request keeps its stored value; a blank or null value clears it. A key that is not (or no
 * longer) an active field is refused unless it carries the stored value unchanged.
 */
public class FieldValidator {

    public static final int MAX_TEXT = 1000;
    public static final int MAX_LONG_TEXT = 10_000;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PHONE = Pattern.compile("\\+?[0-9]{6,15}");

    private final Predicate<String> isBrandUser;

    public FieldValidator(Predicate<String> isBrandUser) {
        this.isBrandUser = isBrandUser;
    }

    /** The map to store, or a {@link ValidationException} with one {@code customFields.<key>} entry per problem. */
    public Map<String, Object> validate(List<CrmFieldDefinition> defs, Map<String, Object> stored,
            Map<String, Object> incoming, boolean creating, boolean enforceRequired) {
        Map<String, String> errors = new LinkedHashMap<>();
        Map<String, Object> out = validate(defs, stored, incoming, creating, enforceRequired, errors);
        if (!errors.isEmpty()) throw new ValidationException(errors);
        return out;
    }

    /** As {@link #validate(List, Map, Map, boolean, boolean)}, adding problems to {@code errors} instead. */
    public Map<String, Object> validate(List<CrmFieldDefinition> defs, Map<String, Object> stored,
            Map<String, Object> incoming, boolean creating, boolean enforceRequired, Map<String, String> errors) {
        Map<String, Object> before = stored == null ? Map.of() : stored;
        Map<String, Object> out = new LinkedHashMap<>(before);
        if (incoming == null && !creating) return out;
        Map<String, Object> sent = incoming == null ? Map.of() : incoming;
        Map<String, CrmFieldDefinition> byKey = new LinkedHashMap<>();
        for (CrmFieldDefinition d : defs) byKey.put(d.getKey(), d);

        for (Map.Entry<String, Object> e : sent.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            CrmFieldDefinition def = key == null ? null : byKey.get(key);
            if (def == null || !def.isActive()) {
                if (key != null && before.containsKey(key) && same(value, before.get(key))) continue;
                errors.put("customFields." + key, def == null ? "is not a field" : "is no longer in use");
                continue;
            }
            if (blank(value)) {
                out.remove(key);
                continue;
            }
            Object normal = normalise(def, value, before.get(key), errors);
            if (normal != null) out.put(key, normal);
        }

        for (CrmFieldDefinition def : defs) {
            if (!def.isActive()) continue;
            String key = def.getKey();
            if (creating && !sent.containsKey(key) && !blank(def.getDefaultValue())) {
                Object normal = normalise(def, def.getDefaultValue(), null, errors);
                if (normal != null) out.put(key, normal);
            }
            if (enforceRequired && def.isRequired() && !out.containsKey(key) && (creating || sent.containsKey(key))) {
                errors.putIfAbsent("customFields." + key, "is required");
            }
        }
        return out;
    }

    private Object normalise(CrmFieldDefinition def, Object value, Object stored, Map<String, String> errors) {
        String field = "customFields." + def.getKey();
        try {
            return switch (def.getType()) {
                case TEXT -> text(value, MAX_TEXT);
                case LONG_TEXT -> text(value, MAX_LONG_TEXT);
                case NUMBER -> number(value, false);
                case CURRENCY -> number(value, true);
                case BOOLEAN -> bool(value);
                case DATE -> LocalDate.parse(string(value).trim()).toString();
                case DATETIME -> instant(string(value).trim()).toString();
                case SELECT -> option(def, string(value).trim(), stored);
                case MULTI_SELECT -> options(def, value, stored);
                case URL -> url(string(value).trim());
                case EMAIL -> matching(string(value).trim(), EMAIL, "must be an email address");
                case PHONE -> matching(string(value).replaceAll("[\\s-]", ""), PHONE,
                        "must be 6 to 15 digits, optionally starting with +");
                case USER -> user(string(value).trim());
            };
        } catch (Invalid e) {
            errors.put(field, e.getMessage());
            return null;
        } catch (DateTimeParseException e) {
            errors.put(field, def.getType() == FieldType.DATE ? "must be a date like 2026-10-24"
                    : "must be an ISO-8601 date and time");
            return null;
        }
    }

    private static String text(Object value, int max) {
        String s = value instanceof Number ? value.toString() : string(value).trim();
        if (s.length() > max) throw new Invalid("must be at most " + max + " characters");
        return s;
    }

    private static Object number(Object value, boolean nonNegative) {
        BigDecimal n;
        if (value instanceof Double d && (d.isNaN() || d.isInfinite())) throw new Invalid("must be a finite number");
        if (value instanceof Float f && (f.isNaN() || f.isInfinite())) throw new Invalid("must be a finite number");
        try {
            n = value instanceof Number ? new BigDecimal(value.toString()) : new BigDecimal(string(value).trim());
        } catch (NumberFormatException e) {
            throw new Invalid("must be a number");
        }
        if (nonNegative && n.signum() < 0) throw new Invalid("must not be negative");
        return stored(n);
    }

    /** A Long when integral and in range, else a Double. */
    private static Object stored(BigDecimal n) {
        BigDecimal plain = n.stripTrailingZeros();
        if (plain.scale() <= 0) {
            try {
                return plain.longValueExact();
            } catch (ArithmeticException tooBig) {
                // falls through to a double
            }
        }
        double d = n.doubleValue();
        if (Double.isInfinite(d)) throw new Invalid("is too large");
        return d;
    }

    private static Boolean bool(Object value) {
        if (value instanceof Boolean b) return b;
        String s = string(value).trim();
        if (s.equalsIgnoreCase("true")) return true;
        if (s.equalsIgnoreCase("false")) return false;
        throw new Invalid("must be true or false");
    }

    private static Instant instant(String s) {
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException e) {
            return Instant.parse(s);
        }
    }

    private static String option(CrmFieldDefinition def, String key, Object stored) {
        FieldOption o = def.getOptions().stream().filter(x -> x.key().equals(key)).findFirst()
                .orElseThrow(() -> new Invalid("is not one of the options"));
        if (!o.active() && !key.equals(stored)) throw new Invalid("is no longer an option");
        return key;
    }

    private static List<String> options(CrmFieldDefinition def, Object value, Object stored) {
        if (!(value instanceof Collection<?> list)) throw new Invalid("must be a list of options");
        Set<Object> had = stored instanceof Collection<?> c ? new HashSet<>(c) : Set.of();
        Set<String> seen = new HashSet<>();
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            String key = string(item).trim();
            if (!seen.add(key)) throw new Invalid("must not repeat an option");
            FieldOption o = def.getOptions().stream().filter(x -> x.key().equals(key)).findFirst()
                    .orElseThrow(() -> new Invalid(key + " is not one of the options"));
            if (!o.active() && !had.contains(key)) throw new Invalid(key + " is no longer an option");
            out.add(key);
        }
        return out;
    }

    private static String url(String s) {
        try {
            URI u = new URI(s);
            String scheme = u.getScheme();
            if (scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    && u.getHost() != null) return s;
        } catch (Exception bad) {
            // reported below
        }
        throw new Invalid("must be a web address starting with http:// or https://");
    }

    private static String matching(String s, Pattern p, String message) {
        if (!p.matcher(s).matches()) throw new Invalid(message);
        return s;
    }

    private String user(String id) {
        if (!isBrandUser.test(id)) throw new Invalid("must be a user of this brand");
        return id;
    }

    private static String string(Object value) {
        if (value instanceof String s) return s;
        throw new Invalid("has the wrong type");
    }

    static boolean blank(Object value) {
        return value == null || (value instanceof String s && s.isBlank())
                || (value instanceof Collection<?> c && c.isEmpty());
    }

    /** Equal values, numbers compared by value whatever their Java type. */
    static boolean same(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return new BigDecimal(x.toString()).compareTo(new BigDecimal(y.toString())) == 0;
        }
        if (a instanceof Collection<?> x && b instanceof Collection<?> y) {
            return new ArrayList<>(x).equals(new ArrayList<>(y));
        }
        return Objects.equals(a, b);
    }

    private static final class Invalid extends RuntimeException {
        Invalid(String message) {
            super(message, null, false, false);
        }
    }
}
