package me.nageshk.omnichannel.access;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** One audited write to a role. Kept after the role is deleted. */
@Document("role_changes")
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

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getRoleId() { return roleId; }
    public void setRoleId(String roleId) { this.roleId = roleId; }
    public String getRoleName() { return roleName; }
    public void setRoleName(String roleName) { this.roleName = roleName; }
    public String getChangedBy() { return changedBy; }
    public void setChangedBy(String changedBy) { this.changedBy = changedBy; }
    public Instant getAt() { return at; }
    public void setAt(Instant at) { this.at = at; }
    public Action getAction() { return action; }
    public void setAction(Action action) { this.action = action; }
    public List<String> getAdded() { return added; }
    public void setAdded(List<String> added) { this.added = added; }
    public List<String> getRemoved() { return removed; }
    public void setRemoved(List<String> removed) { this.removed = removed; }
    public String getDescriptionBefore() { return descriptionBefore; }
    public void setDescriptionBefore(String descriptionBefore) { this.descriptionBefore = descriptionBefore; }
    public String getDescriptionAfter() { return descriptionAfter; }
    public void setDescriptionAfter(String descriptionAfter) { this.descriptionAfter = descriptionAfter; }
}
