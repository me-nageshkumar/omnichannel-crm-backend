package me.nageshk.omnichannel.access;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** One audited write to an authority. Kept after the authority is deleted. */
@Document("authority_changes")
public class AuthorityChange {

    public enum Action { CREATED, UPDATED, DELETED }

    @Id
    private String id;

    @Indexed
    private String code;
    private Action action;
    private String groupBefore;
    private String groupAfter;
    private RoleLevel levelBefore;
    private RoleLevel levelAfter;
    private String descriptionBefore;
    private String descriptionAfter;
    private String changedBy;
    private Instant at;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public Action getAction() { return action; }
    public void setAction(Action action) { this.action = action; }
    public String getGroupBefore() { return groupBefore; }
    public void setGroupBefore(String groupBefore) { this.groupBefore = groupBefore; }
    public String getGroupAfter() { return groupAfter; }
    public void setGroupAfter(String groupAfter) { this.groupAfter = groupAfter; }
    public RoleLevel getLevelBefore() { return levelBefore; }
    public void setLevelBefore(RoleLevel levelBefore) { this.levelBefore = levelBefore; }
    public RoleLevel getLevelAfter() { return levelAfter; }
    public void setLevelAfter(RoleLevel levelAfter) { this.levelAfter = levelAfter; }
    public String getDescriptionBefore() { return descriptionBefore; }
    public void setDescriptionBefore(String descriptionBefore) { this.descriptionBefore = descriptionBefore; }
    public String getDescriptionAfter() { return descriptionAfter; }
    public void setDescriptionAfter(String descriptionAfter) { this.descriptionAfter = descriptionAfter; }
    public String getChangedBy() { return changedBy; }
    public void setChangedBy(String changedBy) { this.changedBy = changedBy; }
    public Instant getAt() { return at; }
    public void setAt(Instant at) { this.at = at; }
}
