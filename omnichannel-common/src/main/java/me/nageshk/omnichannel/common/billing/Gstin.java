package me.nageshk.omnichannel.common.billing;

import java.util.Optional;
import java.util.regex.Pattern;

/** A GST identification number: checked for its shape and its state, not its check digit. */
public final class Gstin {

    private static final Pattern SHAPE = Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$");

    private Gstin() {}

    /** What is wrong with the number for a party in the state; empty when it is fine or not given. */
    public static Optional<String> problem(String gstin, String stateCode) {
        if (gstin == null || gstin.isBlank()) return Optional.empty();
        if (!SHAPE.matcher(gstin).matches()) return Optional.of("is not a GSTIN");
        if (stateCode != null && !gstin.startsWith(stateCode)) return Optional.of("does not belong to the chosen state");
        return Optional.empty();
    }
}
