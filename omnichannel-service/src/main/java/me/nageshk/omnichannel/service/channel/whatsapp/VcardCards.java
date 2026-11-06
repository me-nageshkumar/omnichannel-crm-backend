package me.nageshk.omnichannel.service.channel.whatsapp;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.nageshk.omnichannel.common.channel.whatsapp.webhook.ContactCard;

/** Reads vCard text into Meta's {@code contacts} objects: {@code FN}, {@code N} and every {@code TEL} line. */
final class VcardCards {

    private static final Pattern CARD = Pattern.compile("(?is)BEGIN:VCARD(.*?)END:VCARD");
    private static final Pattern FORMATTED_NAME = Pattern.compile("(?im)^FN(?:;[^:\\r\\n]*)?:(.*)$");
    private static final Pattern NAME = Pattern.compile("(?im)^N(?:;[^:\\r\\n]*)?:(.*)$");
    private static final Pattern TEL = Pattern.compile("(?im)^(?:[\\w-]+\\.)?TEL((?:;[^:\\r\\n]*)?):(.*)$");
    private static final Pattern WAID = Pattern.compile("(?i)waid=([^;:]+)");
    private static final Pattern TYPE = Pattern.compile("(?i)type=([^;:]+)");

    private VcardCards() {
    }

    /** The cards of every text, in order; a text with no {@code BEGIN:VCARD} is read as one card. */
    static List<ContactCard> parse(List<String> texts) {
        List<ContactCard> cards = new ArrayList<>();
        for (String text : texts) {
            Matcher m = CARD.matcher(text);
            boolean any = false;
            while (m.find()) {
                any = true;
                cards.add(card(m.group(1)));
            }
            if (!any) cards.add(card(text));
        }
        return cards;
    }

    private static ContactCard card(String vcard) {
        String formatted = first(FORMATTED_NAME, vcard);
        String[] parts = split(first(NAME, vcard));
        String last = parts.length > 0 ? blankToNull(parts[0]) : null;
        String firstName = parts.length > 1 ? blankToNull(parts[1]) : null;
        ContactCard.Name name = formatted == null && last == null && firstName == null
                ? null : new ContactCard.Name(formatted, firstName, last);
        List<ContactCard.Phone> phones = new ArrayList<>();
        for (Matcher t = TEL.matcher(vcard); t.find(); ) {
            String phone = blankToNull(t.group(2).strip());
            if (phone == null) continue;
            phones.add(new ContactCard.Phone(phone, param(WAID, t.group(1)), param(TYPE, t.group(1))));
        }
        return new ContactCard(name, phones.isEmpty() ? null : phones);
    }

    private static String first(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? blankToNull(m.group(1).strip()) : null;
    }

    private static String param(Pattern p, String params) {
        Matcher m = p.matcher(params);
        return m.find() ? blankToNull(m.group(1).strip()) : null;
    }

    private static String[] split(String n) {
        return n == null ? new String[0] : n.split(";", -1);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
