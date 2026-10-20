package me.nageshk.omnichannel.enablement;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** One audited enable or disable of a number, brand, customer, login or brand access. */
@Document("enablement_changes")
public class EnablementChange {

    @Id
    private String id;

    private String targetType;
    @Indexed
    private String targetId;
    private boolean enabledBefore;
    private boolean enabledAfter;
    private String by;
    private EnableLevel level;
    private Instant at;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }
    public boolean isEnabledBefore() { return enabledBefore; }
    public void setEnabledBefore(boolean enabledBefore) { this.enabledBefore = enabledBefore; }
    public boolean isEnabledAfter() { return enabledAfter; }
    public void setEnabledAfter(boolean enabledAfter) { this.enabledAfter = enabledAfter; }
    public String getBy() { return by; }
    public void setBy(String by) { this.by = by; }
    public EnableLevel getLevel() { return level; }
    public void setLevel(EnableLevel level) { this.level = level; }
    public Instant getAt() { return at; }
    public void setAt(Instant at) { this.at = at; }
}
