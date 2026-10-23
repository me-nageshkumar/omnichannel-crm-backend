package me.nageshk.omnichannel.service.contact;

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

    /** Prefixes {@code countryCode} to a 10-digit number; anything else, or a blank code, is returned unchanged. */
    public static String withDefaultCountryCode(String digits, String countryCode) {
        if (digits == null || countryCode == null || countryCode.isBlank()) return digits;
        return digits.matches("[0-9]{10}") ? countryCode + digits : digits;
    }

    public static boolean isValid(String normalised) {
        return normalised != null && VALID.matcher(normalised).matches();
    }
}
