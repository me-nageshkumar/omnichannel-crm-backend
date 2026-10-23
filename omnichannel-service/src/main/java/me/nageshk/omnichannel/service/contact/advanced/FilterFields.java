package me.nageshk.omnichannel.service.contact.advanced;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** The fields a rule may filter on and the operators each field type accepts. */
public final class FilterFields {

    public enum Type { TEXT, ENUM, BOOLEAN, DATE, LABELS }

    public static final String ATTRIBUTE_PREFIX = "attributes.";

    /** Operators that take no value. */
    public static final Set<String> NO_VALUE = Set.of("isSet", "isNotSet", "isEmpty", "isNotEmpty");

    private static final Map<String, Type> FIELDS = Map.of(
            "name", Type.TEXT,
            "phone", Type.TEXT,
            "source", Type.ENUM,
            "numberHidden", Type.BOOLEAN,
            "doNotContact", Type.BOOLEAN,
            "creationTime", Type.DATE,
            "lastMessageAt", Type.DATE,
            "labels", Type.LABELS);

    private static final Map<Type, Set<String>> OPERATORS = Map.of(
            Type.TEXT, Set.of("is", "isNot", "contains", "notContains", "startsWith", "endsWith", "isSet", "isNotSet"),
            Type.ENUM, Set.of("is", "isNot", "in", "notIn"),
            Type.BOOLEAN, Set.of("is"),
            Type.DATE, Set.of("before", "after", "between", "isSet", "isNotSet"),
            Type.LABELS, Set.of("has", "hasNot", "hasAny", "hasAll", "hasNone", "isEmpty", "isNotEmpty"));

    private FilterFields() {}

    public static Optional<Type> typeOf(String field) {
        if (field == null) {
            return Optional.empty();
        }
        if (field.startsWith(ATTRIBUTE_PREFIX)) {
            return field.length() > ATTRIBUTE_PREFIX.length() ? Optional.of(Type.TEXT) : Optional.empty();
        }
        return Optional.ofNullable(FIELDS.get(field));
    }

    public static Set<String> operators(Type type) {
        return OPERATORS.get(type);
    }
}
