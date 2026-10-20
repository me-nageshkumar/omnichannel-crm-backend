package me.nageshk.omnichannel.template;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import me.nageshk.omnichannel.contact.Phones;
import org.springframework.stereotype.Component;

/** Per-type content rules and placeholder positions; rewrites checked text into its normalised form. */
@Component
public class TemplateValidator {

    public static final int MAX_BODY = 4096;
    public static final int MAX_PLACEHOLDERS = 20;
    public static final int MAX_LOCATION_TEXT = 256;
    public static final int MAX_CARD_TEXT = 256;
    public static final int MAX_QUESTION = 255;
    public static final int MAX_OPTION = 100;
    public static final int MIN_OPTIONS = 2;
    public static final int MAX_OPTIONS = 12;

    private static final String NO_PLACEHOLDERS = "placeholders are not allowed here";
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+$");

    /**
     * Checks the content's fields for the type, adding field errors keyed {@code content.<field>} to {@code errors}.
     * Text that passes is rewritten in place with its placeholders normalised ({@code {{ x }}} becomes {@code {{x}}}).
     *
     * @return the distinct placeholders across all fields that allow them, in field order
     */
    public List<PlaceholderRef> validate(TemplateType type, TemplateContent content, Map<String, String> errors) {
        Map<String, PlaceholderRef> found = new LinkedHashMap<>();
        if (content == null) {
            errors.put("content", "is required");
            return new ArrayList<>();
        }
        switch (type) {
            case TEXT -> text(content, errors, found);
            case LOCATION -> location(content, errors, found);
            case CONTACT_CARD -> contactCard(content, errors, found);
            case POLL -> poll(content, errors, found);
            default -> errors.put("type", "not supported yet");
        }
        return new ArrayList<>(found.values());
    }

    /** A copy of the content holding only the fields the type uses, with defaults applied. */
    public TemplateContent clean(TemplateType type, TemplateContent in) {
        TemplateContent out = new TemplateContent();
        switch (type) {
            case TEXT -> {
                out.setBody(in.getBody());
                out.setLinkPreview(in.getLinkPreview() == null ? Boolean.TRUE : in.getLinkPreview());
            }
            case LOCATION -> {
                out.setLatitude(in.getLatitude());
                out.setLongitude(in.getLongitude());
                out.setName(in.getName());
                out.setAddress(in.getAddress());
            }
            case CONTACT_CARD -> {
                out.setFullName(in.getFullName());
                out.setPhone(in.getPhone());
                out.setOrganization(in.getOrganization());
                out.setEmail(in.getEmail());
            }
            case POLL -> {
                out.setQuestion(in.getQuestion());
                out.setOptions(in.getOptions());
                out.setAllowMultiple(Boolean.TRUE.equals(in.getAllowMultiple()));
            }
            default -> { }
        }
        return out;
    }

    private void text(TemplateContent c, Map<String, String> errors, Map<String, PlaceholderRef> found) {
        String body = c.getBody();
        if (body == null || body.isBlank() || body.length() > MAX_BODY) {
            errors.put("content.body", "must be 1 to " + MAX_BODY + " characters");
            return;
        }
        c.setBody(placeholders("content.body", body, errors, found));
    }

    private void location(TemplateContent c, Map<String, String> errors, Map<String, PlaceholderRef> found) {
        Double lat = c.getLatitude();
        if (lat == null || lat.isNaN() || lat < -90 || lat > 90) {
            errors.put("content.latitude", "must be a number from -90 to 90");
        }
        Double lon = c.getLongitude();
        if (lon == null || lon.isNaN() || lon < -180 || lon > 180) {
            errors.put("content.longitude", "must be a number from -180 to 180");
        }
        c.setName(optional("content.name", c.getName(), MAX_LOCATION_TEXT, errors, found));
        c.setAddress(optional("content.address", c.getAddress(), MAX_LOCATION_TEXT, errors, found));
    }

    private void contactCard(TemplateContent c, Map<String, String> errors, Map<String, PlaceholderRef> found) {
        String full = c.getFullName() == null ? "" : c.getFullName().strip();
        if (full.isEmpty() || full.length() > MAX_CARD_TEXT) {
            errors.put("content.fullName", "must be 1 to " + MAX_CARD_TEXT + " characters");
        } else {
            c.setFullName(placeholders("content.fullName", full, errors, found));
        }
        String rawPhone = c.getPhone() == null ? null : c.getPhone().strip();
        if (rawPhone != null && rawPhone.contains("{{")) {
            errors.put("content.phone", NO_PLACEHOLDERS);
        } else {
            String phone = Phones.normalise(rawPhone);
            if (Phones.isValid(phone)) {
                c.setPhone(phone);
            } else {
                errors.put("content.phone", "must be 8 to 15 digits starting with the country code");
            }
        }
        c.setOrganization(optional("content.organization", c.getOrganization(), MAX_CARD_TEXT, errors, found));
        String email = c.getEmail() == null || c.getEmail().isBlank() ? null : c.getEmail().strip();
        if (email != null && email.contains("{{")) {
            errors.put("content.email", NO_PLACEHOLDERS);
        } else if (email != null && (email.length() > MAX_CARD_TEXT || !EMAIL.matcher(email).matches())) {
            errors.put("content.email", "must be a valid email address of at most " + MAX_CARD_TEXT + " characters");
        }
        c.setEmail(email);
    }

    private void poll(TemplateContent c, Map<String, String> errors, Map<String, PlaceholderRef> found) {
        String question = c.getQuestion() == null ? "" : c.getQuestion().strip();
        if (question.isEmpty() || question.length() > MAX_QUESTION) {
            errors.put("content.question", "must be 1 to " + MAX_QUESTION + " characters");
        } else {
            c.setQuestion(placeholders("content.question", question, errors, found));
        }
        List<String> options = c.getOptions();
        if (options == null || options.size() < MIN_OPTIONS || options.size() > MAX_OPTIONS) {
            errors.put("content.options", "must have " + MIN_OPTIONS + " to " + MAX_OPTIONS + " options");
            return;
        }
        List<String> clean = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String o : options) {
            String t = o == null ? "" : o.strip();
            if (t.isEmpty() || t.length() > MAX_OPTION) {
                errors.put("content.options", "each option must be 1 to " + MAX_OPTION + " characters");
                return;
            }
            String normalised = placeholders("content.options", t, errors, found);
            if (errors.containsKey("content.options")) return;
            if (!seen.add(normalised.toLowerCase())) {
                errors.put("content.options", "options must be distinct, ignoring case");
                return;
            }
            clean.add(normalised);
        }
        c.setOptions(clean);
        c.setAllowMultiple(Boolean.TRUE.equals(c.getAllowMultiple()));
    }

    /** An optional text field that may hold placeholders: trimmed, blank becomes null, length checked first. */
    private String optional(String field, String text, int max, Map<String, String> errors,
            Map<String, PlaceholderRef> found) {
        String t = text == null || text.isBlank() ? null : text.strip();
        if (t == null) return null;
        if (t.length() > max) {
            errors.put(field, "must be at most " + max + " characters");
            return t;
        }
        return placeholders(field, t, errors, found);
    }

    /** Parses one text field; returns the normalised text, or the original if it was rejected. */
    private static String placeholders(String field, String text, Map<String, String> errors,
            Map<String, PlaceholderRef> found) {
        try {
            List<PlaceholderRef> refs = PlaceholderParser.parse(text);
            Set<String> names = new HashSet<>(found.keySet());
            refs.forEach(r -> names.add(r.name()));
            if (names.size() > MAX_PLACEHOLDERS) {
                errors.put(field, "has more than " + MAX_PLACEHOLDERS + " distinct placeholders");
                return text;
            }
            refs.forEach(r -> found.putIfAbsent(r.name(), r));
            return PlaceholderParser.normalise(text);
        } catch (PlaceholderSyntaxException e) {
            errors.put(field, e.getMessage());
            return text;
        }
    }
}
