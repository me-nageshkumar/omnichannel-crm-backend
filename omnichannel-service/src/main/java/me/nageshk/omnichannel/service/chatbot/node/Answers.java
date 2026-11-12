package me.nageshk.omnichannel.service.chatbot.node;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import me.nageshk.omnichannel.db.inbox.WaWebMessage;
import me.nageshk.omnichannel.service.chatbot.flow.settings.AnswerType;

/** What counts as a valid answer per {@link AnswerType} (spec §4.2), and the typed value stored. */
public final class Answers {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern NUMBER = Pattern.compile("^[+-]?\\d+(\\.\\d+)?$");
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
    private static final Set<String> MEDIA = Set.of("image", "video", "audio", "document", "sticker");

    private Answers() {}

    public static Optional<Object> parse(AnswerType type, WaWebMessage m) {
        if (type == null || m == null) return Optional.empty();
        String body = m.getBody() == null ? "" : m.getBody().strip();
        String mtype = m.getType() == null ? "" : m.getType();
        return switch (type) {
            case TEXT -> body.isEmpty() || MEDIA.contains(mtype) && m.getMedia() != null && body.isEmpty() ? Optional.empty()
                    : textOnly(mtype) && !body.isEmpty() ? Optional.of(body) : Optional.empty();
            case NUMBER -> {
                String n = body.replace(",", "").replace(" ", "");
                yield NUMBER.matcher(n).matches() ? Optional.of(new BigDecimal(n)) : Optional.empty();
            }
            case EMAIL -> EMAIL.matcher(body).matches() ? Optional.of(body) : Optional.empty();
            case PHONE -> {
                String digits = body.replaceAll("[+\\-()\\s.]", "");
                yield digits.matches("\\d{9,15}") ? Optional.of(digits) : Optional.empty();
            }
            case DATE -> date(body);
            case LOCATION -> {
                if (!"location".equals(mtype) || m.getLocation() == null || m.getLocation().latitude() == null
                        || m.getLocation().longitude() == null) yield Optional.empty();
                Map<String, Object> loc = new LinkedHashMap<>();
                loc.put("latitude", m.getLocation().latitude());
                loc.put("longitude", m.getLocation().longitude());
                yield Optional.of(loc);
            }
            case FILE -> file(m, mtype, MEDIA);
            case IMAGE -> file(m, mtype, Set.of("image"));
            case VIDEO -> file(m, mtype, Set.of("video"));
            case AUDIO -> file(m, mtype, Set.of("audio"));
            case DOCUMENT -> file(m, mtype, Set.of("document"));
        };
    }

    private static boolean textOnly(String type) {
        return !MEDIA.contains(type) && !"location".equals(type);
    }

    private static Optional<Object> date(String body) {
        for (DateTimeFormatter f : new DateTimeFormatter[] {DMY, ISO}) {
            try {
                return Optional.of(LocalDate.parse(body, f).toString());
            } catch (DateTimeParseException ignored) {
                // try the next format
            }
        }
        return Optional.empty();
    }

    private static Optional<Object> file(WaWebMessage m, String type, Set<String> allowed) {
        if (!allowed.contains(type) || m.getMedia() == null) return Optional.empty();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("messageId", m.getId());
        out.put("mimeType", m.getMedia().mimetype());
        out.put("filename", m.getMedia().filename());
        out.put("size", m.getMedia().size());
        return Optional.of(out);
    }
}
