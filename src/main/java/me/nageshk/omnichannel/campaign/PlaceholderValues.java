package me.nageshk.omnichannel.campaign;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.contact.Contact;
import org.springframework.stereotype.Component;

/** The value of each of a campaign's placeholders for one recipient, by placeholder name. */
@Component
class PlaceholderValues {

    /** {@code contact} may be null (a CSV row nobody has messaged yet); a blank value takes the mapping's fallback. */
    Map<String, String> of(Campaign c, CampaignRecipient r, Contact contact) {
        Map<String, String> out = new LinkedHashMap<>();
        if (c.getPlaceholders() == null) return out;
        for (Campaign.PlaceholderMapping m : c.getPlaceholders()) {
            String value = m.source() == null ? null : valueOf(m.source(), c, r, contact);
            out.put(m.name(), value == null || value.isBlank() ? m.fallback() : value);
        }
        return out;
    }

    private static String valueOf(Campaign.PlaceholderSource s, Campaign c, CampaignRecipient r, Contact contact) {
        return switch (s.kind()) {
            case CONTACT_FIELD -> "phone".equals(s.field()) ? "+" + r.getPhone()
                    : contact == null ? null : contact.getName();
            case CONTACT_ATTRIBUTE -> contact == null || contact.getAttributes() == null ? null
                    : contact.getAttributes().get(s.attributeId());
            case CSV_COLUMN -> cell(c.getAudience(), r, s.column());
            case FIXED -> s.text();
        };
    }

    /** The recipient's cell under {@code column}; the phone column gives the normalised phone. */
    static String cell(Campaign.Audience a, CampaignRecipient r, String column) {
        if (a == null || column == null) return null;
        if (column.equals(a.phoneColumn())) return "+" + r.getPhone();
        List<String> headers = a.csvHeaders();
        int at = headers == null ? -1 : headers.indexOf(column);
        List<String> cells = r.getValues();
        return at < 0 || cells == null || at >= cells.size() ? null : cells.get(at);
    }
}
