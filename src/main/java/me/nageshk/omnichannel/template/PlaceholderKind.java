package me.nageshk.omnichannel.template;

/** Where a placeholder's value comes from: a contact field, a contact attribute, or the agent at send time. */
public enum PlaceholderKind {
    CONTACT_FIELD,
    CONTACT_ATTRIBUTE,
    AGENT
}
