package me.nageshk.omnichannel.service.inbox;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the contact cards (vCard text) that a {@code vcard} or {@code multi_vcard} message carries in its body. */
final class ContactCards {

    private static final Pattern BEGIN = Pattern.compile("(?im)^BEGIN:VCARD\\s*$");
    private static final Pattern FORMATTED_NAME = Pattern.compile("(?im)^FN(?:;[^:\\r\\n]*)?:(.*)$");

    private ContactCards() {
    }

    /** "👤 Wife" for one card with a name, "👤 2 contacts" for several, else "👤 Contact card". */
    static String preview(String body) {
        if (body == null) return "👤 Contact card";
        int cards = 0;
        for (Matcher m = BEGIN.matcher(body); m.find(); ) cards++;
        if (cards > 1) return "👤 " + cards + " contacts";
        Matcher name = FORMATTED_NAME.matcher(body);
        String fn = name.find() ? name.group(1).strip() : "";
        return fn.isEmpty() ? "👤 Contact card" : "👤 " + fn;
    }
}
