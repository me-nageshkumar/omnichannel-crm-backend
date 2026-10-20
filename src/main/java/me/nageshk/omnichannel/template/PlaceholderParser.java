package me.nageshk.omnichannel.template;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** The placeholder grammar gate: finds and checks every {@code {{name}}} in a text, rejecting all other Mustache. */
public final class PlaceholderParser {

    private static final String SIGILS = "#^/>!{&=";
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]{0,39}");
    private static final Pattern PATH_CHARS = Pattern.compile("[A-Za-z0-9_.]+");

    private PlaceholderParser() {}

    /** The distinct placeholders in order of first appearance; throws on any syntax the grammar rejects. */
    public static List<PlaceholderRef> parse(String text) {
        Map<String, PlaceholderRef> refs = new LinkedHashMap<>();
        scan(text, refs);
        return new ArrayList<>(refs.values());
    }

    /** The text with every {@code {{  x  }}} rewritten to {@code {{x}}}; throws on any syntax the grammar rejects. */
    public static String normalise(String text) {
        return scan(text, new LinkedHashMap<>());
    }

    private static String scan(String text, Map<String, PlaceholderRef> refs) {
        if (text == null) return null;
        StringBuilder out = new StringBuilder(text.length());
        int pos = 0;
        while (true) {
            int open = text.indexOf("{{", pos);
            if (open < 0) break;
            out.append(text, pos, open);
            int i = open + 2;
            while (i < text.length() && text.charAt(i) == ' ') i++;
            if (i < text.length() && SIGILS.indexOf(text.charAt(i)) >= 0) {
                throw new PlaceholderSyntaxException("\"{{" + text.charAt(i)
                        + "\" is not allowed — only {{name}} placeholders are supported");
            }
            int close = text.indexOf("}}", open + 2);
            if (close < 0) throw new PlaceholderSyntaxException("unclosed \"{{\"");
            String raw = text.substring(open + 2, close);
            PlaceholderRef ref = classify(raw.strip(), raw);
            refs.putIfAbsent(ref.name(), ref);
            out.append("{{").append(ref.name()).append("}}");
            pos = close + 2;
        }
        return out.append(text, pos, text.length()).toString();
    }

    private static PlaceholderRef classify(String inner, String raw) {
        if (!PATH_CHARS.matcher(inner).matches()) throw invalid(raw);
        if (inner.equals("contact") || inner.startsWith("contact.")) {
            if (inner.equals("contact.name") || inner.equals("contact.phone")) {
                return new PlaceholderRef(inner, PlaceholderKind.CONTACT_FIELD, null);
            }
            String prefix = "contact.attributes.";
            if (inner.startsWith(prefix) && inner.length() > prefix.length()) {
                String key = inner.substring(prefix.length());
                if (!NAME.matcher(key).matches()) throw invalid(raw);
                return new PlaceholderRef(inner, PlaceholderKind.CONTACT_ATTRIBUTE, key);
            }
            throw new PlaceholderSyntaxException("\"{{" + inner + "}}\" is not a contact field");
        }
        if (!NAME.matcher(inner).matches()) throw invalid(raw);
        return new PlaceholderRef(inner, PlaceholderKind.AGENT, null);
    }

    private static PlaceholderSyntaxException invalid(String raw) {
        return new PlaceholderSyntaxException("invalid placeholder \"{{" + raw + "}}\"");
    }
}
