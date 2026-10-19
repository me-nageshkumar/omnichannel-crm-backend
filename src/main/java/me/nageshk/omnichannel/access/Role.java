package me.nageshk.omnichannel.access;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** A named bundle of authorities. Authority codes are strings; unknown (removed) codes may linger. */
@Document("roles")
public class Role {

    @Id
    private String id;

    @Indexed(unique = true)
    private String name;

    private String description;
    private RoleLevel level;
    private Set<String> authorities = new HashSet<>();
    private boolean builtIn;

    @CreatedDate
    private Instant creationTime;

    @LastModifiedDate
    private Instant lastUpdateTime;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public RoleLevel getLevel() { return level; }
    public void setLevel(RoleLevel level) { this.level = level; }
    public Set<String> getAuthorities() { return authorities; }
    public void setAuthorities(Set<String> authorities) { this.authorities = authorities; }
    public boolean isBuiltIn() { return builtIn; }
    public void setBuiltIn(boolean builtIn) { this.builtIn = builtIn; }
    public Instant getCreationTime() { return creationTime; }
    public void setCreationTime(Instant creationTime) { this.creationTime = creationTime; }
    public Instant getLastUpdateTime() { return lastUpdateTime; }
    public void setLastUpdateTime(Instant lastUpdateTime) { this.lastUpdateTime = lastUpdateTime; }
}
