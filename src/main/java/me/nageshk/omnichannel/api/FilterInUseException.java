package me.nageshk.omnichannel.api;

import java.util.List;

/** A label or attribute used by advanced filters or unfinished campaigns cannot be deleted: 409 {@code FILTER_IN_USE}. */
public class FilterInUseException extends RuntimeException {

    public static final String CODE = "FILTER_IN_USE";
    public static final String MESSAGE = "This is used by a saved filter or a campaign. Remove it there first.";

    /** A saved filter or campaign naming the thing in use. */
    public record Ref(String id, String name) {}

    private final List<Ref> filters;
    private final List<Ref> campaigns;

    public FilterInUseException(List<Ref> filters, List<Ref> campaigns) {
        super(MESSAGE);
        this.filters = List.copyOf(filters);
        this.campaigns = List.copyOf(campaigns);
    }

    public List<Ref> filters() { return filters; }

    public List<Ref> campaigns() { return campaigns; }
}
