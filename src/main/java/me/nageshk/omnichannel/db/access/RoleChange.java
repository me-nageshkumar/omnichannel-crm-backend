package me.nageshk.omnichannel.db.access;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** One audited write to a role. Kept after the role is deleted. */
@Document("roleChanges")
@Getter
@Setter
public class RoleChange {

    public enum Action { CREATED, UPDATED, DELETED }

    @Id
    private String id;

    @Indexed
    private String roleId;
    private String roleName;
    private String changedBy;
    private Instant at;
    private Action action;
    private List<String> added = new ArrayList<>();
    private List<String> removed = new ArrayList<>();
    private String descriptionBefore;
    private String descriptionAfter;
}
