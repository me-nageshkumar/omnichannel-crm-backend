package me.nageshk.omnichannel.contact;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** A free-text field a brand defines for its contacts; the name is unique per brand ignoring case. */
@Document("contact_attributes")
@CompoundIndex(name = "ws_nameKey", def = "{'workspaceId': 1, 'nameKey': 1}", unique = true)
@Getter
@Setter
public class ContactAttribute {

    @Id
    private String id;
    private String workspaceId;
    private String name;
    private String nameKey;
    private String description;

    /** Also sets the case-folded key used for the uniqueness index. */
    public void setName(String name) {
        this.name = name;
        this.nameKey = name == null ? null : name.toLowerCase();
    }
}
