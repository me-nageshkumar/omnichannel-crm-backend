package me.nageshk.omnichannel.contact;

import java.time.Instant;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** A note on a contact, with its author. */
@Document("contact_notes")
@CompoundIndex(name = "ws_contact", def = "{'workspaceId': 1, 'contactId': 1}")
public class ContactNote {

    @Id
    private String id;
    private String workspaceId;
    private String contactId;
    private String text;
    private String createdByUsername;
    private String createdByName;
    @CreatedDate
    private Instant creationTime;
    @LastModifiedDate
    private Instant lastUpdateTime;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getContactId() { return contactId; }
    public void setContactId(String contactId) { this.contactId = contactId; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getCreatedByUsername() { return createdByUsername; }
    public void setCreatedByUsername(String createdByUsername) { this.createdByUsername = createdByUsername; }
    public String getCreatedByName() { return createdByName; }
    public void setCreatedByName(String createdByName) { this.createdByName = createdByName; }
    public Instant getCreationTime() { return creationTime; }
    public void setCreationTime(Instant creationTime) { this.creationTime = creationTime; }
    public Instant getLastUpdateTime() { return lastUpdateTime; }
    public void setLastUpdateTime(Instant lastUpdateTime) { this.lastUpdateTime = lastUpdateTime; }
}
