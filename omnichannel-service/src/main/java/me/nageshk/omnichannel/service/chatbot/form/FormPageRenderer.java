package me.nageshk.omnichannel.service.chatbot.form;

import com.samskivert.mustache.Escapers;
import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.Template;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.service.chatbot.flow.settings.AskFormSettings;
import me.nageshk.omnichannel.service.chatbot.flow.settings.FormField;
import me.nageshk.omnichannel.service.chatbot.flow.settings.FormFieldType;
import org.springframework.stereotype.Component;

/** The form page (spec §8.4): our own HTML with inline CSS and JS, the message page's look, no external requests. */
@Component
public class FormPageRenderer {

    private static final String CLOSED_TEXT = "This form is closed";
    private static final String DEFAULT_THANKS = "Thank you, your answers were received.";

    private final Template form;
    private final Template notice;
    private final String css;
    private final String js;

    public FormPageRenderer() {
        Mustache.Compiler compiler = Mustache.compiler().withEscaper(Escapers.simple(new String[][] {
                {"&", "&amp;"}, {"<", "&lt;"}, {">", "&gt;"}, {"\"", "&quot;"}, {"'", "&#39;"}}))
                .nullValue("").defaultValue("");
        form = compiler.compile(resource("pages/form.mustache"));
        notice = compiler.compile(resource("pages/notice.mustache"));
        css = resource("pages/form.css");
        js = resource("pages/form.js");
    }

    /** The form with the given values filled in and errors shown; {@code action} is the POST path. */
    public String form(AskFormSettings s, String action, Map<String, String> values, Map<String, String> errors) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("css", css);
        ctx.put("js", js);
        ctx.put("title", s.title());
        ctx.put("intro", s.intro() == null || s.intro().isBlank() ? null : s.intro());
        ctx.put("action", action);
        ctx.put("submitText", s.submitText() == null || s.submitText().isBlank() ? "Submit" : s.submitText());
        List<Map<String, Object>> fields = new ArrayList<>();
        if (s.fields() != null) {
            for (FormField f : s.fields()) {
                FormFieldType type = f.type() == null ? FormFieldType.TEXT : f.type();
                String value = values == null ? null : values.get(f.key());
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("key", f.key());
                m.put("label", f.label());
                m.put("required", f.required());
                m.put("value", value == null ? "" : value);
                m.put("error", errors == null ? null : errors.get(f.key()));
                m.put("isSelect", type == FormFieldType.CHOICE);
                m.put("isTextarea", type == FormFieldType.LONG_TEXT);
                m.put("isInput", type != FormFieldType.CHOICE && type != FormFieldType.LONG_TEXT);
                m.put("inputType", inputType(type));
                m.put("step", type == FormFieldType.NUMBER);
                List<Map<String, Object>> options = new ArrayList<>();
                if (f.options() != null) {
                    for (String o : f.options()) {
                        Map<String, Object> om = new LinkedHashMap<>();
                        om.put("value", o);
                        om.put("selected", o.equals(value));
                        options.add(om);
                    }
                }
                m.put("options", options);
                fields.add(m);
            }
        }
        ctx.put("fields", fields);
        return form.execute(ctx);
    }

    public String thanks(AskFormSettings s) {
        String text = s.thanksText() == null || s.thanksText().isBlank() ? DEFAULT_THANKS : s.thanksText();
        return notice(s.title() == null ? "Thank you" : s.title(), text);
    }

    public String closed() {
        return notice(CLOSED_TEXT, "This form was already submitted or is no longer open.");
    }

    public String notFound() {
        return notice("This link is not valid", "Check the link you were sent.");
    }

    private String notice(String title, String text) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("css", css);
        ctx.put("title", title);
        ctx.put("text", text);
        return notice.execute(ctx);
    }

    private static String inputType(FormFieldType type) {
        return switch (type) {
            case EMAIL -> "email";
            case PHONE -> "tel";
            case NUMBER -> "number";
            case DATE -> "date";
            default -> "text";
        };
    }

    private static String resource(String path) {
        try (InputStream in = FormPageRenderer.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing classpath resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
