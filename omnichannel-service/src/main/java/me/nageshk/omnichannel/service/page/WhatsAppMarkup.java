package me.nageshk.omnichannel.service.page;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WhatsApp's {@code *bold*}, {@code _italic_}, {@code ~strike~} and ```mono``` as HTML, the way the console's
 * {@code formatWhatsApp} shows them: the text is escaped first, a span's inside stays literal (no nesting), and newlines
 * become {@code <br>}.
 */
public final class WhatsAppMarkup {

    private static final Pattern FORMAT = Pattern.compile(
            "```([\\s\\S]+?)```|\\*(?!\\s)([^*\\n]*?[^\\s*])\\*|_(?!\\s)([^_\\n]*?[^\\s_])_|~(?!\\s)([^~\\n]*?[^\\s~])~");
    private static final String[] TAGS = {"code", "strong", "em", "s"};

    private WhatsAppMarkup() {}

    public static String toHtml(String text) {
        if (text == null) return "";
        String escaped = escape(text);
        StringBuilder out = new StringBuilder(escaped.length() + 16);
        Matcher m = FORMAT.matcher(escaped);
        int last = 0;
        while (m.find()) {
            out.append(escaped, last, m.start());
            for (int g = 1; g <= 4; g++) {
                if (m.group(g) != null) {
                    out.append('<').append(TAGS[g - 1]).append('>').append(m.group(g))
                            .append("</").append(TAGS[g - 1]).append('>');
                    break;
                }
            }
            last = m.end();
        }
        out.append(escaped, last, escaped.length());
        return out.toString().replace("\n", "<br>");
    }

    /** The text without its marks, newlines as spaces: for previews and OG tags. */
    public static String plain(String text) {
        if (text == null) return "";
        Matcher m = FORMAT.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        int last = 0;
        while (m.find()) {
            out.append(text, last, m.start());
            for (int g = 1; g <= 4; g++) {
                if (m.group(g) != null) {
                    out.append(m.group(g));
                    break;
                }
            }
            last = m.end();
        }
        out.append(text, last, text.length());
        return out.toString().replace("\n", " ");
    }

    /** HTML-escapes {@code & < > " '}. */
    public static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
