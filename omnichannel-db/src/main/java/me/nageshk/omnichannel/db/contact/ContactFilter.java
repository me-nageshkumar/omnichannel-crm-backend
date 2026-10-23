package me.nageshk.omnichannel.db.contact;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import me.nageshk.omnichannel.db.contact.advanced.RuleGroup;
import me.nageshk.omnichannel.db.contact.advanced.Uses;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** A named contact filter shared by everyone in the brand. */
@Document("contactFilters")
@CompoundIndex(name = "ws_nameKey", def = "{'workspaceId': 1, 'nameKey': 1}", unique = true)
@Getter
@Setter
public class ContactFilter {

    @Id
    private String id;
    private String workspaceId;
    private String name;
    private String nameKey;
    /** Set for SIMPLE filters; null for ADVANCED ones. */
    private ContactFilterSpec filter;
    /** Set for ADVANCED filters, as sent by the client. */
    private RuleGroup rules;
    /** The compiled query of {@link #rules}, written by the server only; carries no workspace clause. */
    private String queryJson;
    private Integer queryVersion;
    private Uses uses;
    private FilterKind kind;
    private String createdBy;
    @CreatedDate
    private Instant creationTime;
    @LastModifiedDate
    private Instant lastUpdateTime;

    /** Also sets the case-folded key used for the uniqueness index. */
    public void setName(String name) {
        this.name = name;
        this.nameKey = name == null ? null : name.toLowerCase();
    }
}
