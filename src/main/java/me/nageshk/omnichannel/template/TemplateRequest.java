package me.nageshk.omnichannel.template;

import java.util.List;

/** Body of create and update; {@code type} is ignored on update and {@code version} is required there. */
public record TemplateRequest(String name, String category, TemplateType type, TemplateContent content,
        String mediaId, List<PlaceholderSetting> placeholders, Long version) {

    /** The author's settings for one placeholder; which ones apply depends on its kind. */
    public record PlaceholderSetting(String name, String fallback, String label, String example, Boolean required) {}
}
