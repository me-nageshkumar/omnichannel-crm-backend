package me.nageshk.omnichannel.service.contact;

import com.fasterxml.jackson.annotation.JsonRawValue;
import java.time.Instant;
import me.nageshk.omnichannel.db.contact.ContactFilter;
import me.nageshk.omnichannel.db.contact.ContactFilterSpec;
import me.nageshk.omnichannel.db.contact.FilterKind;
import me.nageshk.omnichannel.db.contact.advanced.RuleGroup;
import me.nageshk.omnichannel.db.contact.advanced.Uses;

/** API view of a saved contact filter; {@code query} is the stored compiled query, written raw. */
public record FilterView(String id, String name, FilterKind kind, ContactFilterSpec filter, RuleGroup rules,
        @JsonRawValue String query, Uses uses, String createdBy, Instant creationTime, Instant lastUpdateTime) {

    public static FilterView of(ContactFilter f) {
        return new FilterView(f.getId(), f.getName(), FilterKind.orSimple(f.getKind()), f.getFilter(), f.getRules(),
                f.getQueryJson(), f.getUses(), f.getCreatedBy(), f.getCreationTime(), f.getLastUpdateTime());
    }
}
