package me.nageshk.omnichannel.template;

import lombok.Getter;
import lombok.Setter;

/** A placeholder found in a template's content, with the settings the author gave it. */
@Getter
@Setter
public class TemplatePlaceholder {

    /** The text between the braces, e.g. {@code order_id} or {@code contact.attributes.city}. */
    private String name;
    private PlaceholderKind kind;
    private String attributeKey;
    private String attributeId;
    private String label;
    private String example;
    private boolean required;
    private String fallback;
}
