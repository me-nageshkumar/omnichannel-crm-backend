package me.nageshk.omnichannel.common.api;

/** A template that has been used cannot be deleted: 409 {@code TEMPLATE_IN_USE}. */
public class TemplateInUseException extends RuntimeException {

    public static final String CODE = "TEMPLATE_IN_USE";

    public TemplateInUseException() {
        super("This template has been used; archive it instead");
    }
}
