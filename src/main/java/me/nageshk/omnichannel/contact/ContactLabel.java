package me.nageshk.omnichannel.contact;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** A coloured tag a brand can put on contacts; the name is unique per brand ignoring case. */
@Document("contactLabels")
@CompoundIndex(name = "ws_nameKey", def = "{'workspaceId': 1, 'nameKey': 1}", unique = true)
@Getter
@Setter
public class ContactLabel {

    @Id
    private String id;
    private String workspaceId;
    private String name;
    private String nameKey;
    private String color;
    private String description;

    /** Also sets the case-folded key used for the uniqueness index. */
    public void setName(String name) {
        this.name = name;
        this.nameKey = name == null ? null : name.toLowerCase();
    }
}
