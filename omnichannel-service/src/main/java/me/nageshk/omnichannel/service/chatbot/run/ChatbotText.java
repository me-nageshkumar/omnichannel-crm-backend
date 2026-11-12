package me.nageshk.omnichannel.service.chatbot.run;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code {{path}}} variables over a run's nested variables (spec §4.4): lenient (an unknown path is empty text), no
 * HTML escaping, no sections. For JSON bodies every value is JSON-escaped, so a quote in an answer cannot break the
 * request. Resolution is our own rather than JMustache's: a missing first segment must render empty, not fail.
 */
public final class ChatbotText {

    private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)*)\\s*}}");

    private ChatbotText() {}

    public static String render(String template, Map<String, Object> vars) {
        return render(template, vars, false);
    }

    public static String renderJson(String template, Map<String, Object> vars) {
        return render(template, vars, true);
    }

    private static String render(String template, Map<String, Object> vars, boolean json) {
        if (template == null || template.isEmpty()) return "";
        Matcher m = VARIABLE.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            Object value = lookup(vars == null ? Map.of() : vars, m.group(1));
            String text = value == null ? "" : String.valueOf(value);
            m.appendReplacement(out, Matcher.quoteReplacement(json && value instanceof String ? escape(text) : text));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** The value at a dotted path through nested maps, or null. */
    public static Object lookup(Map<String, Object> vars, String path) {
        Object cur = vars;
        for (String part : path.split("\\.")) {
            if (!(cur instanceof Map<?, ?> m)) return null;
            cur = m.get(part);
            if (cur == null) return null;
        }
        return cur;
    }

    /** The {@code {{a.b}}} paths a template names (section tags are not variables). */
    public static Set<String> paths(String template) {
        Set<String> out = new LinkedHashSet<>();
        if (template == null) return out;
        Matcher m = VARIABLE.matcher(template);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /** Rewrites {@code {{old.} } to {@code {{new.} } (whitespace after the braces kept), nothing else. */
    public static String renameNode(String template, String oldName, String newName) {
        if (template == null) return null;
        Pattern p = Pattern.compile("\\{\\{(\\s*)" + Pattern.quote(oldName) + "\\.");
        return p.matcher(template).replaceAll(r -> "{{" + r.group(1) + Matcher.quoteReplacement(newName) + ".");
    }

    /** JSON string escaping without the surrounding quotes. */
    static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                case '\b' -> b.append("\\b");
                case '\f' -> b.append("\\f");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.toString();
    }
}
