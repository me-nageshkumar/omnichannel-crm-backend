package me.nageshk.omnichannel.user;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** A platform user. Field names are stable: JWT claims and API responses reuse them. */
@Document("users")
public class User {

    @Id
    private String id;

    private String name;

    @Indexed(unique = true)
    private String username;

    /** Bare bcrypt hash (no {@code {bcrypt}} prefix). */
    private String password;

    private String email;
    private String mobileNumber;
    /** Null for company-less users; the platform role, or the company-wide role inside a customer. */
    private String roleId;
    /** Null for platform users. */
    private String customerId;
    private List<WorkspaceRole> workspaceRoles = new ArrayList<>();
    private boolean mustChangePassword;
    private boolean active;
    private TwoFactor twoFactor;

    /** Bumping this invalidates every issued access token for the user. */
    private long tokenVersion;

    @CreatedDate
    private Instant creationTime;

    @LastModifiedDate
    private Instant lastUpdateTime;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getMobileNumber() { return mobileNumber; }
    public void setMobileNumber(String mobileNumber) { this.mobileNumber = mobileNumber; }
    public String getRoleId() { return roleId; }
    public void setRoleId(String roleId) { this.roleId = roleId; }
    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    public List<WorkspaceRole> getWorkspaceRoles() { return workspaceRoles; }
    public void setWorkspaceRoles(List<WorkspaceRole> workspaceRoles) {
        this.workspaceRoles = workspaceRoles == null ? new ArrayList<>() : workspaceRoles;
    }
    public boolean isMustChangePassword() { return mustChangePassword; }
    public void setMustChangePassword(boolean mustChangePassword) { this.mustChangePassword = mustChangePassword; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public TwoFactor getTwoFactor() { return twoFactor; }
    public void setTwoFactor(TwoFactor twoFactor) { this.twoFactor = twoFactor; }
    public long getTokenVersion() { return tokenVersion; }
    public void setTokenVersion(long tokenVersion) { this.tokenVersion = tokenVersion; }
    public Instant getCreationTime() { return creationTime; }
    public void setCreationTime(Instant creationTime) { this.creationTime = creationTime; }
    public Instant getLastUpdateTime() { return lastUpdateTime; }
    public void setLastUpdateTime(Instant lastUpdateTime) { this.lastUpdateTime = lastUpdateTime; }
}
