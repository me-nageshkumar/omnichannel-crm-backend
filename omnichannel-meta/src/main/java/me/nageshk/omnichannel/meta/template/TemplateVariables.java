package me.nageshk.omnichannel.meta.template;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.nageshk.omnichannel.common.channel.whatsapp.message.MediaObject;
import me.nageshk.omnichannel.common.channel.whatsapp.message.Template;
import me.nageshk.omnichannel.common.channel.whatsapp.message.TemplateSnapshot;
import tools.jackson.databind.JsonNode;

/**
 * A Meta template's blanks, read from its components as Meta returns them, and the {@code template} send object
 * built from values (spec 2026-11-20 §5.6). Keys: {@code header.<n|name>}, {@code body.<n|name>},
 * {@code button.<index>}.
 */
public final class TemplateVariables {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]+)\\s*}}");
    private static final Set<String> MEDIA_HEADERS = Set.of("IMAGE", "VIDEO", "DOCUMENT");
    private static final Set<String> PLAIN_BUTTONS = Set.of("QUICK_REPLY", "PHONE_NUMBER", "URL", "COPY_CODE");

    private TemplateVariables() {}

    public record Variable(String key, String component, int index, String example) {}

    public record Derived(List<Variable> variables, String headerFormat, boolean supported, String unsupportedReason) {}

    public record Built(Template template, String renderedBody, TemplateSnapshot snapshot) {}

    public static Derived derive(JsonNode components, String parameterFormat, String category) {
        List<Variable> vars = new ArrayList<>();
        String header = "NONE";
        List<String> unsupported = new ArrayList<>();
        if ("AUTHENTICATION".equalsIgnoreCase(category)) unsupported.add("AUTHENTICATION templates");
        for (JsonNode c : components) {
            String type = c.path("type").asString("").toUpperCase();
            switch (type) {
                case "HEADER" -> {
                    String format = c.path("format").asString("TEXT").toUpperCase();
                    header = format;
                    if ("TEXT".equals(format)) {
                        List<String> names = placeholders(c.path("text").asString(""));
                        for (int i = 0; i < names.size(); i++) {
                            vars.add(new Variable("header." + names.get(i), "HEADER", i,
                                    example(c.path("example"), "header_text", "header_text_named_params", names.get(i), i)));
                        }
                    } else if (!MEDIA_HEADERS.contains(format)) {
                        unsupported.add(format + " headers");
                    }
                }
                case "BODY" -> {
                    List<String> names = placeholders(c.path("text").asString(""));
                    for (int i = 0; i < names.size(); i++) {
                        vars.add(new Variable("body." + names.get(i), "BODY", i,
                                example(c.path("example"), "body_text", "body_text_named_params", names.get(i), i)));
                    }
                }
                case "FOOTER" -> { }
                case "BUTTONS" -> {
                    JsonNode buttons = c.path("buttons");
                    for (int i = 0; i < buttons.size(); i++) {
                        JsonNode b = buttons.get(i);
                        String bt = b.path("type").asString("").toUpperCase();
                        if (!PLAIN_BUTTONS.contains(bt)) {
                            unsupported.add(bt + " buttons");
                        } else if ("URL".equals(bt) && !placeholders(b.path("url").asString("")).isEmpty()) {
                            vars.add(new Variable("button." + i, "BUTTON", i, b.path("example").path(0).asString(null)));
                        } else if ("COPY_CODE".equals(bt)) {
                            vars.add(new Variable("button." + i, "BUTTON", i, b.path("example").asString(null)));
                        }
                    }
                }
                default -> unsupported.add(type.isEmpty() ? "an unknown part" : type);
            }
        }
        return new Derived(vars, header, unsupported.isEmpty(),
                unsupported.isEmpty() ? null : "Not supported yet: " + String.join(", ", unsupported));
    }

    /** The keys of {@code d} with no value (blank counts as none), in template order. */
    public static List<String> missing(Derived d, Map<String, String> values) {
        List<String> out = new ArrayList<>();
        for (Variable v : d.variables()) {
            String value = values == null ? null : values.get(v.key());
            if (value == null || value.isBlank()) out.add(v.key());
        }
        return out;
    }

    /**
     * Meta's {@code template} object: a header parameter (text, or the media by Meta's id), the body parameters in
     * order (named ones carry {@code parameter_name}), and one component per URL or copy-code button with a value.
     */
    public static Built build(String name, String language, JsonNode components, String parameterFormat,
            Map<String, String> values, String headerMetaMediaId) {
        boolean named = "NAMED".equalsIgnoreCase(parameterFormat);
        List<Template.Component> out = new ArrayList<>();
        TemplateSnapshot.Header header = null;
        String body = null;
        String footer = null;
        List<TemplateSnapshot.Button> buttons = new ArrayList<>();
        for (JsonNode c : components) {
            String type = c.path("type").asString("").toUpperCase();
            switch (type) {
                case "HEADER" -> {
                    String format = c.path("format").asString("TEXT").toUpperCase();
                    if ("TEXT".equals(format)) {
                        String text = c.path("text").asString("");
                        List<String> names = placeholders(text);
                        if (!names.isEmpty()) {
                            out.add(new Template.Component("header", null, null, params(names, "header", values, named)));
                        }
                        header = new TemplateSnapshot.Header("TEXT", fill(text, "header", values), null);
                    } else if (MEDIA_HEADERS.contains(format)) {
                        MediaObject media = new MediaObject(headerMetaMediaId, null, null, null);
                        String lower = format.toLowerCase();
                        Template.Parameter p = new Template.Parameter(lower, null, null, "image".equals(lower) ? media : null,
                                "video".equals(lower) ? media : null, "document".equals(lower) ? media : null, null, null);
                        out.add(new Template.Component("header", null, null, List.of(p)));
                        header = new TemplateSnapshot.Header(format, null, null);
                    }
                }
                case "BODY" -> {
                    String text = c.path("text").asString("");
                    List<String> names = placeholders(text);
                    if (!names.isEmpty()) out.add(new Template.Component("body", null, null, params(names, "body", values, named)));
                    body = fill(text, "body", values);
                }
                case "FOOTER" -> footer = c.path("text").asString(null);
                case "BUTTONS" -> {
                    JsonNode bs = c.path("buttons");
                    for (int i = 0; i < bs.size(); i++) {
                        JsonNode b = bs.get(i);
                        String bt = b.path("type").asString("").toUpperCase();
                        String value = values == null ? null : values.get("button." + i);
                        if ("URL".equals(bt) && !placeholders(b.path("url").asString("")).isEmpty()) {
                            out.add(new Template.Component("button", "url", String.valueOf(i),
                                    List.of(Template.Parameter.text(value, null))));
                        } else if ("COPY_CODE".equals(bt)) {
                            out.add(new Template.Component("button", "copy_code", String.valueOf(i),
                                    List.of(new Template.Parameter("coupon_code", null, null, null, null, null, null, value))));
                        }
                        String label = b.path("text").asString("COPY_CODE".equals(bt) ? "Copy code" : "");
                        buttons.add(new TemplateSnapshot.Button(bt, label));
                    }
                }
                default -> { }
            }
        }
        Template template = new Template(name, new Template.Language(language), out.isEmpty() ? null : out);
        TemplateSnapshot snapshot = new TemplateSnapshot(name, language,
                header == null ? new TemplateSnapshot.Header("NONE", null, null) : header, body, footer, buttons);
        return new Built(template, body, snapshot);
    }

    private static List<Template.Parameter> params(List<String> names, String part, Map<String, String> values,
            boolean named) {
        List<Template.Parameter> out = new ArrayList<>();
        for (String n : names) {
            out.add(Template.Parameter.text(values == null ? null : values.get(part + "." + n), named ? n : null));
        }
        return out;
    }

    private static String fill(String text, String part, Map<String, String> values) {
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = values == null ? null : values.get(part + "." + m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? m.group() : v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** The distinct placeholders of a text in order: {@code {{1}}} → "1", {@code {{first_name}}} → "first_name". */
    static List<String> placeholders(String text) {
        Set<String> seen = new LinkedHashSet<>();
        Matcher m = PLACEHOLDER.matcher(text == null ? "" : text);
        while (m.find()) seen.add(m.group(1));
        List<String> out = new ArrayList<>(seen);
        if (out.stream().allMatch(s -> s.matches("\\d+"))) out.sort((a, b) -> Integer.compare(Integer.parseInt(a),
                Integer.parseInt(b)));
        return out;
    }

    private static String example(JsonNode example, String positional, String namedField, String name, int i) {
        JsonNode named = example.path(namedField);
        if (named.isArray()) {
            for (JsonNode p : named) if (name.equals(p.path("param_name").asString(""))) return p.path("example").asString(null);
        }
        JsonNode pos = example.path(positional);
        // body_text is a list of lists, header_text a list
        JsonNode row = pos.path(0).isArray() ? pos.path(0) : pos;
        return row.path(i).isMissingNode() ? null : row.path(i).asString(null);
    }
}
