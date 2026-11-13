package me.nageshk.omnichannel.service.chatbot.form;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import me.nageshk.omnichannel.service.chatbot.flow.settings.AskFormSettings;
import me.nageshk.omnichannel.service.chatbot.flow.settings.FormField;
import me.nageshk.omnichannel.service.chatbot.flow.settings.FormFieldType;

/** Server-side validation of a submitted form (spec §8.4) and the typed answers it yields. */
public final class FormAnswers {

    static final int MAX_LENGTH = 1000;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern NUMBER = Pattern.compile("^[+-]?\\d+(\\.\\d+)?$");
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    private FormAnswers() {}

    /** Field key to one problem; empty when the submission is clean. */
    public static Map<String, String> validate(AskFormSettings s, Map<String, String> values) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (s.fields() == null) return errors;
        for (FormField f : s.fields()) {
            String raw = values == null ? null : values.get(f.key());
            String v = raw == null ? "" : raw.strip();
            if (v.isEmpty()) {
                if (f.required()) errors.put(f.key(), "is required");
                continue;
            }
            if (v.length() > MAX_LENGTH) {
                errors.put(f.key(), "must be at most " + MAX_LENGTH + " characters");
                continue;
            }
            FormFieldType type = f.type() == null ? FormFieldType.TEXT : f.type();
            String problem = switch (type) {
                case NUMBER -> NUMBER.matcher(v.replace(",", "").replace(" ", "")).matches() ? null : "must be a number";
                case EMAIL -> EMAIL.matcher(v).matches() ? null : "must be an email address";
                case PHONE -> v.replaceAll("[+\\-()\\s.]", "").matches("\\d{9,15}") ? null : "must be a phone number";
                case DATE -> date(v) == null ? "must be a date (dd/mm/yyyy)" : null;
                case CHOICE -> f.options() != null && f.options().contains(v) ? null : "choose one of the options";
                default -> null;
            };
            if (problem != null) errors.put(f.key(), problem);
        }
        return errors;
    }

    /** The typed answers of a clean submission, keyed by field; empty fields are left out. */
    public static Map<String, Object> clean(AskFormSettings s, Map<String, String> values) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (s.fields() == null) return out;
        for (FormField f : s.fields()) {
            String raw = values == null ? null : values.get(f.key());
            String v = raw == null ? "" : raw.strip();
            if (v.isEmpty()) continue;
            FormFieldType type = f.type() == null ? FormFieldType.TEXT : f.type();
            Object typed = switch (type) {
                case NUMBER -> new BigDecimal(v.replace(",", "").replace(" ", ""));
                case PHONE -> v.replaceAll("[+\\-()\\s.]", "");
                case DATE -> date(v);
                default -> v;
            };
            out.put(f.key(), typed);
        }
        return out;
    }

    private static String date(String v) {
        for (DateTimeFormatter f : new DateTimeFormatter[] {DMY, ISO}) {
            try {
                return LocalDate.parse(v, f).toString();
            } catch (DateTimeParseException ignored) {
                // next format
            }
        }
        return null;
    }
}
