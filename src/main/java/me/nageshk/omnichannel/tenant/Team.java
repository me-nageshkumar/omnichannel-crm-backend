package me.nageshk.omnichannel.tenant;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** A group of users inside a workspace. */
@Document("teams")
@CompoundIndex(name = "workspace_name_unique", def = "{'workspaceId': 1, 'name': 1}", unique = true)
@Getter
@Setter
public class Team {

    @Id
    private String id;

    private String customerId;
    private String workspaceId;
    private String name;
    private List<String> memberIds = new ArrayList<>();
    /** The member round robin assigned last, across every number the team owns (design §3.4). */
    private String roundRobinCursor;

    /** Optimistic lock: a save from a stale copy fails instead of undoing a concurrent membership change. */
    @Version
    private Long version;

    @CreatedDate
    private Instant creationTime;

    @LastModifiedDate
    private Instant lastUpdateTime;

    public void setMemberIds(List<String> memberIds) { this.memberIds = memberIds == null ? new ArrayList<>() : memberIds; }
}
