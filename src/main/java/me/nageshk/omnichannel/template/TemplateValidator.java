package me.nageshk.omnichannel.template;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Per-type content rules and placeholder positions; rewrites checked text into its normalised form. */
@Component
public class TemplateValidator {

    public static final int MAX_BODY = 4096;
    public static final int MAX_PLACEHOLDERS = 20;

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
        if (type == TemplateType.TEXT) {
            text(content, errors, found);
        } else {
            errors.put("type", "not supported yet");
        }
        return new ArrayList<>(found.values());
    }

    /** A copy of the content holding only the fields the type uses, with defaults applied. */
    public TemplateContent clean(TemplateType type, TemplateContent in) {
        TemplateContent out = new TemplateContent();
        if (type == TemplateType.TEXT) {
            out.setBody(in.getBody());
            out.setLinkPreview(in.getLinkPreview() == null ? Boolean.TRUE : in.getLinkPreview());
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
