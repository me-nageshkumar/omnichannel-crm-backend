package me.nageshk.omnichannel.service.automation;

/** Fills a deal-title pattern for a contact. */
public final class DealTitles {

    public static final String DEFAULT = "{{name}} – WhatsApp";

    private DealTitles() {}

    /**
     * {@code {{name}}} is the contact's name, else {@code +<phone>}, else "WhatsApp contact"; {@code {{phone}}} is
     * {@code +<phone>} or empty. An empty pattern means {@link #DEFAULT}.
     */
    public static String fill(String pattern, String name, String phone) {
        String p = pattern == null || pattern.isBlank() ? DEFAULT : pattern;
        boolean hasPhone = phone != null && !phone.isBlank();
        String plus = hasPhone ? "+" + phone.trim() : "";
        String who = name != null && !name.isBlank() ? name.trim() : hasPhone ? plus : "WhatsApp contact";
        return p.replace("{{name}}", who).replace("{{phone}}", plus).trim();
    }
}
