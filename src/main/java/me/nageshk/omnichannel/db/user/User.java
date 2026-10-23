package me.nageshk.omnichannel.db.user;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import me.nageshk.omnichannel.db.enablement.Disabled;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** A platform user. Field names are stable: JWT claims and API responses reuse them. */
@Document("users")
@Getter
@Setter
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
    private List<BrandDisable> brandDisables = new ArrayList<>();
    private boolean mustChangePassword;
    private boolean active;
    private Disabled disabled;
    private TwoFactor twoFactor;
    /** Null means AVAILABLE: existing users never set it. */
    private Availability availability;

    /** Bumping this invalidates every issued access token for the user. */
    private long tokenVersion;

    /** Optimistic lock: a save from a stale copy fails instead of undoing a deactivation, reset or role change. */
    @Version
    private Long version;

    @CreatedDate
    private Instant creationTime;

    @LastModifiedDate
    private Instant lastUpdateTime;

    public void setWorkspaceRoles(List<WorkspaceRole> workspaceRoles) {
        this.workspaceRoles = workspaceRoles == null ? new ArrayList<>() : workspaceRoles;
    }
    /** The status routing should read: AVAILABLE until the user says otherwise. */
    public Availability.Status availabilityStatus() {
        return availability == null ? Availability.Status.AVAILABLE : availability.status();
    }

    public List<BrandDisable> getBrandDisables() { return brandDisables == null ? new ArrayList<>() : brandDisables; }
}
