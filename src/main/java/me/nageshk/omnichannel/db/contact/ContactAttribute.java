package me.nageshk.omnichannel.db.contact;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** A free-text field a brand defines for its contacts; the name is unique per brand ignoring case, the key never changes. */
@Document("contactAttributes")
@CompoundIndex(name = "ws_nameKey", def = "{'workspaceId': 1, 'nameKey': 1}", unique = true)
@CompoundIndex(name = "ws_key", def = "{'workspaceId': 1, 'key': 1}", unique = true,
        partialFilter = "{'key': {$type: 'string'}}")
@Getter
@Setter
public class ContactAttribute {

    @Id
    private String id;
    private String workspaceId;
    private String name;
    private String nameKey;
    /** Immutable placeholder-safe identifier, unique per brand; absent on attributes saved before keys existed. */
    private String key;
    private String description;

    /** Also sets the case-folded key used for the uniqueness index. */
    public void setName(String name) {
        this.name = name;
        this.nameKey = name == null ? null : name.toLowerCase();
    }
}
