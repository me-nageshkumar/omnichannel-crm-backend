package me.nageshk.omnichannel.template;

import java.util.List;

/**
 * A template with every placeholder filled: {@code content} holds the rendered text, {@code vcard} is set for contact
 * cards, {@code missing} names the required agent placeholders that had no value, and {@code warnings} are the
 * template's attribute warnings.
 */
public record RenderedTemplate(TemplateType type, TemplateContent content, MediaView media, String vcard,
        List<String> missing, List<String> warnings) {}
