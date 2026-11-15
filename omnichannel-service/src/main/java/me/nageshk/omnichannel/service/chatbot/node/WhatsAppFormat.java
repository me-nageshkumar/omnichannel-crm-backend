package me.nageshk.omnichannel.service.chatbot.node;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The AI's markdown as WhatsApp formatting (spec §5.9): {@code **b**} → {@code *b*}, {@code *i*} and {@code __i__} →
 * {@code _i_}, {@code ~~s~~} → {@code ~s~}, headings → bold lines, {@code [t](u)} → {@code t (u)}, {@code * item} →
 * {@code - item}; code (fenced or inline) is kept as it is. The text is then cut into parts of at most
 * {@value #MAX_PART} characters, at a paragraph, else a line, else a sentence, else a word end.
 */
public final class WhatsAppFormat {

    /** WhatsApp's limit on a text message body. */
    public static final int MAX_PART = 4096;

    /** Private-use characters: a code placeholder's bounds, and a bold star that later steps must not touch. */
    private static final char CODE = '';
    private static final char STAR = '';

    private static final Pattern FENCE = Pattern.compile("```[\\s\\S]*?```");
    private static final Pattern INLINE_CODE = Pattern.compile("`[^`\\n]+`");
    private static final Pattern LINK = Pattern.compile("(?<!!)\\[([^\\]\\n]+)]\\(([^)\\s]+)\\)");
    private static final Pattern HEADING = Pattern.compile("(?m)^[ \\t]{0,3}#{1,6}[ \\t]+(.+?)[ \\t]*#*[ \\t]*$");
    private static final Pattern BULLET = Pattern.compile("(?m)^([ \\t]*)[*+][ \\t]+");
    private static final Pattern BOLD = Pattern.compile("\\*\\*(?=\\S)(.+?)(?<=\\S)\\*\\*");
    private static final Pattern BOLD_UNDERSCORE = Pattern.compile("(?<![\\w_])__(?=\\S)(.+?)(?<=\\S)__(?![\\w_])");
    private static final Pattern ITALIC = Pattern.compile("(?<![\\w*])\\*(?=\\S)([^*\\n]+?)(?<=\\S)\\*(?![\\w*])");
    private static final Pattern STRIKE = Pattern.compile("~~(?=\\S)(.+?)(?<=\\S)~~");
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?](?=\\s)");

    private WhatsAppFormat() {}

    /** The parts to send in order; empty when there is nothing to say. */
    public static List<String> convert(String markdown) {
        if (markdown == null || markdown.isBlank()) return List.of();
        List<String> code = new ArrayList<>();
        String s = protect(FENCE, markdown.strip(), code);
        s = protect(INLINE_CODE, s, code);
        s = LINK.matcher(s).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + " (" + m.group(2) + ")"));
        s = HEADING.matcher(s).replaceAll(m -> Matcher.quoteReplacement(STAR + unstarred(m.group(1)) + STAR));
        s = BULLET.matcher(s).replaceAll("$1- ");
        s = BOLD.matcher(s).replaceAll(m -> Matcher.quoteReplacement(STAR + m.group(1) + STAR));
        s = BOLD_UNDERSCORE.matcher(s).replaceAll("_$1_");
        s = ITALIC.matcher(s).replaceAll("_$1_");
        s = STRIKE.matcher(s).replaceAll("~$1~");
        s = s.replace(STAR, '*');
        s = restore(s, code);
        return split(s);
    }

    /** A heading's own text without bold or italic marks (the whole line becomes bold). */
    private static String unstarred(String heading) {
        return heading.replace("**", "").replace("__", "").strip();
    }

    private static String protect(Pattern p, String s, List<String> code) {
        Matcher m = p.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            code.add(m.group());
            m.appendReplacement(out, Matcher.quoteReplacement(CODE + String.valueOf(code.size() - 1) + CODE));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String restore(String s, List<String> code) {
        for (int i = code.size() - 1; i >= 0; i--) s = s.replace(CODE + String.valueOf(i) + CODE, code.get(i));
        return s;
    }

    static List<String> split(String text) {
        List<String> parts = new ArrayList<>();
        String rest = text.strip();
        while (rest.length() > MAX_PART) {
            int cut = cut(rest);
            String part = rest.substring(0, cut).strip();
            if (!part.isEmpty()) parts.add(part);
            rest = rest.substring(cut).strip();
        }
        if (!rest.isEmpty()) parts.add(rest);
        return parts;
    }

    /** Where to cut a text longer than a part: the latest paragraph, line, sentence or word end that fits. */
    private static int cut(String s) {
        String head = s.substring(0, MAX_PART);
        int at = head.lastIndexOf("\n\n");
        if (at > 0) return at;
        at = head.lastIndexOf('\n');
        if (at > 0) return at;
        int sentence = -1;
        Matcher m = SENTENCE_END.matcher(s);
        while (m.find() && m.end() <= MAX_PART) sentence = m.end();
        if (sentence > 0) return sentence;
        at = head.lastIndexOf(' ');
        return at > 0 ? at : MAX_PART;
    }
}
