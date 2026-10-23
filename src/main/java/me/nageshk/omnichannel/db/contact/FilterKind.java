package me.nageshk.omnichannel.db.contact;

/** How a saved filter is expressed: the flat structured spec, or a rule tree compiled on the server. */
public enum FilterKind {
    SIMPLE, ADVANCED;

    /** Filters saved before kinds existed have none and are SIMPLE. */
    public static FilterKind orSimple(FilterKind k) {
        return k == null ? SIMPLE : k;
    }
}
