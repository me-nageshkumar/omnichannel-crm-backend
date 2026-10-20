package me.nageshk.omnichannel.contact;

import java.util.regex.Pattern;

/** Phone-number clean-up and validation for contacts: digits only, country code first, 8 to 15 digits. */
public final class Phones {

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
