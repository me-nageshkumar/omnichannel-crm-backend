package me.nageshk.omnichannel.contact;

import java.time.Instant;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** A named contact filter shared by everyone in the brand. */
@Document("contact_filters")
@CompoundIndex(name = "ws_nameKey", def = "{'workspaceId': 1, 'nameKey': 1}", unique = true)
public class ContactFilter {

    @Id
    private String id;
    private String workspaceId;
    private String name;
    private String nameKey;
    private ContactFilterSpec filter;
    private String createdBy;
    @CreatedDate
    private Instant creationTime;

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
    public ContactFilterSpec getFilter() { return filter; }
    public void setFilter(ContactFilterSpec filter) { this.filter = filter; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreationTime() { return creationTime; }
    public void setCreationTime(Instant creationTime) { this.creationTime = creationTime; }
}
