package me.nageshk.omnichannel.contact;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** A named contact filter shared by everyone in the brand. */
@Document("contact_filters")
@CompoundIndex(name = "ws_nameKey", def = "{'workspaceId': 1, 'nameKey': 1}", unique = true)
@Getter
@Setter
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

    /** Also sets the case-folded key used for the uniqueness index. */
    public void setName(String name) {
        this.name = name;
        this.nameKey = name == null ? null : name.toLowerCase();
    }
}
