package me.nageshk.omnichannel.contact;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** A coloured tag a brand can put on contacts; the name is unique per brand ignoring case. */
@Document("contact_labels")
@CompoundIndex(name = "ws_nameKey", def = "{'workspaceId': 1, 'nameKey': 1}", unique = true)
public class ContactLabel {

    @Id
    private String id;
    private String workspaceId;
    private String name;
    private String nameKey;
    private String color;
    private String description;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getName() { return name; }
    /** Also sets the case-folded key used for the uniqueness index. */
    public void setName(String name) {
        this.name = name;
        this.nameKey = name == null ? null : name.toLowerCase();
    }
    public String getNameKey() { return nameKey; }
    public void setNameKey(String nameKey) { this.nameKey = nameKey; }
    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
