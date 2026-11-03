package me.nageshk.omnichannel.service.automation;

import java.util.Map;
import me.nageshk.omnichannel.db.automation.AutomationEventKind;
import me.nageshk.omnichannel.db.automation.EventData;

/** The one line that names an event in the runs and the history. */
public final class EventSummaries {

    static final int TEXT_LENGTH = 80;

    private static final Map<String, String> KIND_LABELS = Map.of("TEXT", "Text", "PHOTO", "Photo", "VIDEO",
            "Video", "AUDIO", "Audio / voice note", "DOCUMENT", "Document", "STICKER", "Sticker", "LOCATION",
            "Location", "CONTACT_CARD", "Contact card", "POLL", "Poll");

    private EventSummaries() {
    }

    /**
     * {@code "Message from <name | +phone | a contact>: <text cut to 80 | kind label>"}, or
     * {@code "<deal title> moved from <from lane | a removed lane> to <to lane>"}.
     */
    public static String of(AutomationEventKind kind, EventData d) {
        if (kind == AutomationEventKind.DEAL_LANE_CHANGED) {
            String title = d == null || d.deal() == null || blank(d.deal().title()) ? "A deal" : d.deal().title();
            String from = d == null || d.fromLane() == null || blank(d.fromLane().name()) ? "a removed lane"
                    : d.fromLane().name();
            String to = d == null || d.toLane() == null || blank(d.toLane().name()) ? "a removed lane"
                    : d.toLane().name();
            return title + " moved from " + from + " to " + to;
        }
        return "Message from " + who(d == null ? null : d.contact()) + ": " + what(d == null ? null : d.message());
    }

    private static String who(EventData.ContactData c) {
        if (c != null && !blank(c.name())) return c.name().trim();
        if (c != null && !blank(c.phone())) return "+" + c.phone().trim();
        return "a contact";
    }

    private static String what(EventData.MessageData m) {
        if (m != null && !blank(m.text())) {
            String text = m.text().strip().replaceAll("\\s+", " ");
            if (text.codePointCount(0, text.length()) <= TEXT_LENGTH) return text;
            return text.substring(0, text.offsetByCodePoints(0, TEXT_LENGTH)).stripTrailing() + "…";
        }
        String label = m == null || m.type() == null ? null : KIND_LABELS.get(m.type());
        return label == null ? "a message" : label;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
