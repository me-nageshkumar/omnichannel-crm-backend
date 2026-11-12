package me.nageshk.omnichannel.service.cloud;

import static me.nageshk.omnichannel.common.channel.whatsapp.message.CloudMessages.Limits;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.common.channel.whatsapp.message.CloudMessage;
import me.nageshk.omnichannel.common.channel.whatsapp.message.Interactive;
import me.nageshk.omnichannel.common.channel.whatsapp.message.MessagingProduct;
import me.nageshk.omnichannel.service.contact.Phones;
import org.springframework.stereotype.Component;

/**
 * Checks a Cloud API message against Meta's documented limits (spec §3) and our own rules (reply titles are the
 * reply-match key, so they are unique; media comes from an upload, never a link). Error keys are JSON paths into the
 * message, such as {@code interactive.action.buttons.1.reply.title}. Pure: the only lookup is injected.
 */
@Component
public class CloudMessageValidator {

    /** How the validator learns a media id's MIME type in a workspace. */
    @FunctionalInterface
    public interface MediaLookup {
        Optional<String> mimeOf(String workspaceId, String mediaId);
    }

    private static final String REQUIRED = "is required";
    private static final String ABSENT = "must be absent";
    private static final String UNIQUE = "must be unique";
    private static final Set<String> HEADER_TYPES = Set.of("text", "image", "video", "document");

    /**
     * @param toIsPhone whether {@code to} must be a phone (the Cloud-shaped API), or any non-blank id (a chat-keyed send)
     * @return the message with {@code to} normalised
     * @throws ValidationException with every problem found, keyed by JSON path
     */
    public CloudMessage validate(String workspaceId, CloudMessage m, MediaLookup media, boolean toIsPhone) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (!MessagingProduct.WHATSAPP.equals(m.messagingProduct())
                && !MessagingProduct.WHATSAPP_WEB.equals(m.messagingProduct())) {
            errors.put("messaging_product", "must be whatsapp or whatsapp-web");
        }
        if (m.recipientType() != null && !"individual".equals(m.recipientType())) {
            errors.put("recipient_type", "must be individual");
        }
        String to = toIsPhone ? Phones.normalise(m.to()) : m.to();
        if (toIsPhone ? !Phones.isValid(to) : isBlank(to)) {
            errors.put("to", toIsPhone ? Phones.MESSAGE : REQUIRED);
        }
        if (CloudMessage.TEXT.equals(m.type())) {
            if (m.text() == null) errors.put("text", REQUIRED);
            else length(errors, "text.body", m.text().body(), Limits.BODY_TEXT, true);
            if (m.interactive() != null) errors.put("interactive", ABSENT);
        } else if (CloudMessage.INTERACTIVE.equals(m.type())) {
            if (m.interactive() == null) errors.put("interactive", REQUIRED);
            else interactive(workspaceId, m.interactive(), media, errors);
            if (m.text() != null) errors.put("text", ABSENT);
        } else {
            errors.put("type", "must be text or interactive");
        }
        if (!errors.isEmpty()) throw new ValidationException(errors);
        return m.withTo(to);
    }

    /** The reply buttons of a {@code button} message or every row of a {@code list}, else none. */
    public static List<Interactive.Reply> replies(Interactive i) {
        List<Interactive.Reply> out = new ArrayList<>();
        if (i == null || i.action() == null) return out;
        if (Interactive.BUTTON.equals(i.type()) && i.action().buttons() != null) {
            for (Interactive.ActionButton b : i.action().buttons()) if (b != null && b.reply() != null) out.add(b.reply());
        } else if (Interactive.LIST.equals(i.type()) && i.action().sections() != null) {
            for (Interactive.Section s : i.action().sections()) {
                if (s == null || s.rows() == null) continue;
                for (Interactive.Row r : s.rows()) if (r != null) out.add(new Interactive.Reply(r.id(), r.title()));
            }
        }
        return out;
    }

    private void interactive(String ws, Interactive i, MediaLookup media, Map<String, String> errors) {
        String type = i.type();
        if (type == null || !Interactive.INTERACTIVE_TYPES.contains(type)) {
            errors.put("interactive.type", "must be button, list, cta_url or location_request_message");
            return;
        }
        boolean list = Interactive.LIST.equals(type);
        boolean locationRequest = Interactive.LOCATION_REQUEST.equals(type);
        if (i.body() == null || isBlank(i.body().text())) errors.put("interactive.body.text", REQUIRED);
        else length(errors, "interactive.body.text", i.body().text(), list ? Limits.BODY_LIST : Limits.BODY_INTERACTIVE, true);
        if (i.footer() != null) {
            if (locationRequest) errors.put("interactive.footer", ABSENT);
            else length(errors, "interactive.footer.text", i.footer().text(), Limits.FOOTER, true);
        }
        if (i.header() != null) {
            if (locationRequest) errors.put("interactive.header", ABSENT);
            else header(ws, i.header(), list, media, errors);
        }
        Interactive.Action a = i.action() == null ? new Interactive.Action(null, null, null, null, null) : i.action();
        switch (type) {
            case Interactive.BUTTON -> {
                buttons(a, errors);
                absent(errors, "interactive.action.button", a.button());
                absent(errors, "interactive.action.sections", a.sections());
                absent(errors, "interactive.action.name", a.name());
                absent(errors, "interactive.action.parameters", a.parameters());
            }
            case Interactive.LIST -> {
                sections(a, errors);
                absent(errors, "interactive.action.buttons", a.buttons());
                absent(errors, "interactive.action.name", a.name());
                absent(errors, "interactive.action.parameters", a.parameters());
            }
            case Interactive.CTA_URL -> {
                if (!"cta_url".equals(a.name())) errors.put("interactive.action.name", "must be cta_url");
                cta(a.parameters(), errors);
                absent(errors, "interactive.action.buttons", a.buttons());
                absent(errors, "interactive.action.button", a.button());
                absent(errors, "interactive.action.sections", a.sections());
            }
            default -> {
                if (!"send_location".equals(a.name())) errors.put("interactive.action.name", "must be send_location");
                absent(errors, "interactive.action.buttons", a.buttons());
                absent(errors, "interactive.action.button", a.button());
                absent(errors, "interactive.action.sections", a.sections());
                absent(errors, "interactive.action.parameters", a.parameters());
            }
        }
    }

    private void header(String ws, Interactive.Header h, boolean list, MediaLookup media, Map<String, String> errors) {
        String type = h.type();
        if (type == null || !HEADER_TYPES.contains(type)) {
            errors.put("interactive.header.type", "must be text, image, video or document");
            return;
        }
        if (list && !"text".equals(type)) {
            errors.put("interactive.header.type", "must be text");
            return;
        }
        if ("text".equals(type)) {
            if (isBlank(h.text())) errors.put("interactive.header.text", REQUIRED);
            else length(errors, "interactive.header.text", h.text(), Limits.HEADER_TEXT, true);
        } else {
            absent(errors, "interactive.header.text", h.text());
        }
        for (String kind : List.of("image", "video", "document")) {
            Interactive.MediaRef ref = switch (kind) {
                case "image" -> h.image();
                case "video" -> h.video();
                default -> h.document();
            };
            String path = "interactive.header." + kind;
            if (!kind.equals(type)) {
                absent(errors, path, ref);
                continue;
            }
            if (ref == null || isBlank(ref.id())) {
                if (ref != null && !isBlank(ref.link())) {
                    errors.put(path + ".link", "is not supported; upload the file and use its id");
                } else {
                    errors.put(path + ".id", REQUIRED);
                }
                continue;
            }
            if (!isBlank(ref.link())) errors.put(path + ".link", "is not supported; upload the file and use its id");
            Optional<String> mime = media.mimeOf(ws, ref.id());
            if (mime.isEmpty()) errors.put(path + ".id", "unknown media");
            else if (!CloudMediaTypes.forHeader(kind).contains(mime.get())) {
                errors.put(path + ".id", "must be " + CloudMediaTypes.describe(kind));
            }
        }
    }

    private void buttons(Interactive.Action a, Map<String, String> errors) {
        List<Interactive.ActionButton> buttons = a.buttons();
        if (buttons == null || buttons.isEmpty() || buttons.size() > Limits.BUTTONS) {
            errors.put("interactive.action.buttons", "needs 1 to 3 buttons");
            return;
        }
        Set<String> ids = new HashSet<>();
        Set<String> titles = new HashSet<>();
        for (int n = 0; n < buttons.size(); n++) {
            String path = "interactive.action.buttons." + n;
            Interactive.ActionButton b = buttons.get(n);
            if (b == null) {
                errors.put(path, REQUIRED);
                continue;
            }
            if (!"reply".equals(b.type())) errors.put(path + ".type", "must be reply");
            Interactive.Reply r = b.reply();
            if (r == null) {
                errors.put(path + ".reply", REQUIRED);
                continue;
            }
            if (isBlank(r.id())) errors.put(path + ".reply.id", REQUIRED);
            else if (!length(errors, path + ".reply.id", r.id(), Limits.BUTTON_ID, false)) { /* reported */ }
            else if (!ids.add(r.id())) errors.put(path + ".reply.id", UNIQUE);
            if (isBlank(r.title())) errors.put(path + ".reply.title", REQUIRED);
            else if (!length(errors, path + ".reply.title", r.title(), Limits.BUTTON_TITLE, false)) { /* reported */ }
            else if (!titles.add(r.title().toLowerCase(Locale.ROOT))) errors.put(path + ".reply.title", UNIQUE);
        }
    }

    private void sections(Interactive.Action a, Map<String, String> errors) {
        if (isBlank(a.button())) errors.put("interactive.action.button", REQUIRED);
        else length(errors, "interactive.action.button", a.button(), Limits.LIST_BUTTON, false);
        List<Interactive.Section> sections = a.sections();
        if (sections == null || sections.isEmpty() || sections.size() > Limits.SECTIONS) {
            errors.put("interactive.action.sections", "needs 1 to 10 sections");
            return;
        }
        Set<String> ids = new HashSet<>();
        Set<String> titles = new HashSet<>();
        int rows = 0;
        for (int s = 0; s < sections.size(); s++) {
            String path = "interactive.action.sections." + s;
            Interactive.Section section = sections.get(s);
            if (section == null) {
                errors.put(path, REQUIRED);
                continue;
            }
            if (isBlank(section.title())) errors.put(path + ".title", REQUIRED);
            else length(errors, path + ".title", section.title(), Limits.SECTION_TITLE, false);
            if (section.rows() == null || section.rows().isEmpty()) {
                errors.put(path + ".rows", "needs at least 1 row");
                continue;
            }
            for (int n = 0; n < section.rows().size(); n++) {
                rows++;
                String rp = path + ".rows." + n;
                Interactive.Row r = section.rows().get(n);
                if (r == null) {
                    errors.put(rp, REQUIRED);
                    continue;
                }
                if (isBlank(r.id())) errors.put(rp + ".id", REQUIRED);
                else if (!length(errors, rp + ".id", r.id(), Limits.ROW_ID, false)) { /* reported */ }
                else if (!ids.add(r.id())) errors.put(rp + ".id", UNIQUE);
                if (isBlank(r.title())) errors.put(rp + ".title", REQUIRED);
                else if (!length(errors, rp + ".title", r.title(), Limits.ROW_TITLE, false)) { /* reported */ }
                else if (!titles.add(r.title().toLowerCase(Locale.ROOT))) errors.put(rp + ".title", UNIQUE);
                if (r.description() != null) length(errors, rp + ".description", r.description(), Limits.ROW_DESCRIPTION, false);
            }
        }
        if (rows > Limits.ROWS) errors.put("interactive.action.sections", "at most 10 rows in total");
    }

    private void cta(Interactive.CtaParameters p, Map<String, String> errors) {
        if (p == null) {
            errors.put("interactive.action.parameters", REQUIRED);
            return;
        }
        if (isBlank(p.displayText())) errors.put("interactive.action.parameters.display_text", REQUIRED);
        else length(errors, "interactive.action.parameters.display_text", p.displayText(), Limits.CTA_TEXT, false);
        String url = p.url();
        if (isBlank(url)) errors.put("interactive.action.parameters.url", REQUIRED);
        else if (!url.startsWith("https://") || url.length() < 9 || url.chars().anyMatch(Character::isWhitespace)) {
            errors.put("interactive.action.parameters.url", "must start with https://");
        } else length(errors, "interactive.action.parameters.url", url, Limits.URL, false);
    }

    /** Reports an over-length value (or a blank one when {@code required}); true when the value is fine. */
    private static boolean length(Map<String, String> errors, String path, String value, int max, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) errors.put(path, REQUIRED);
            return !required;
        }
        if (value.length() > max) {
            errors.put(path, "must be at most " + max + " characters");
            return false;
        }
        return true;
    }

    private static void absent(Map<String, String> errors, String path, Object value) {
        if (value != null) errors.put(path, ABSENT);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
