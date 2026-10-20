package me.nageshk.omnichannel.contact;

import java.time.Instant;

/** API view of a saved contact filter. */
public record FilterView(String id, String name, ContactFilterSpec filter, String createdBy, Instant creationTime) {

    public static FilterView of(ContactFilter f) {
        return new FilterView(f.getId(), f.getName(), f.getFilter(), f.getCreatedBy(), f.getCreationTime());
    }
}
