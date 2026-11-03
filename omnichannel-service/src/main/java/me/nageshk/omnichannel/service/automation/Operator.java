package me.nageshk.omnichannel.service.automation;

import java.util.Arrays;
import java.util.Optional;

/** The comparison a condition makes; {@link #code()} is the wire form. */
public enum Operator {
    IS("is"), IS_NOT("is_not"), IS_ONE_OF("is_one_of"), CONTAINS("contains"), NOT_CONTAINS("not_contains"),
    STARTS_WITH("starts_with"), ENDS_WITH("ends_with"), IS_EMPTY("is_empty"), IS_NOT_EMPTY("is_not_empty"),
    GREATER_THAN("greater_than"), LESS_THAN("less_than"), AT_LEAST("at_least"), AT_MOST("at_most"),
    BETWEEN("between");

    private final String code;

    Operator(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Optional<Operator> of(String code) {
        return Arrays.stream(values()).filter(o -> o.code.equals(code)).findFirst();
    }
}
