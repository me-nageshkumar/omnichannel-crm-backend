package me.nageshk.omnichannel.contact;

import java.util.regex.Pattern;

/** Phone-number clean-up and validation for contacts: digits only, country code first, 8 to 15 digits. */
public final class Phones {

    /** The sentence a rejected phone is reported with. */
    public static final String MESSAGE = "must be 8 to 15 digits with the country code, e.g. 919812345678";

    private static final Pattern VALID = Pattern.compile("^[1-9][0-9]{7,14}$");

    private Phones() {}

    /** Strips {@code +}, spaces, dashes and brackets; null stays null. */
    public static String normalise(String raw) {
        return raw == null ? null : raw.replaceAll("[+\\s\\-()]", "");
    }

    public static boolean isValid(String normalised) {
        return normalised != null && VALID.matcher(normalised).matches();
    }
}
