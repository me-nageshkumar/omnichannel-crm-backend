package me.nageshk.omnichannel.db.contact;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** A note on a contact, with its author. */
@Document("contactNotes")
@CompoundIndex(name = "ws_contact", def = "{'workspaceId': 1, 'contactId': 1}")
@Getter
@Setter
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
}
