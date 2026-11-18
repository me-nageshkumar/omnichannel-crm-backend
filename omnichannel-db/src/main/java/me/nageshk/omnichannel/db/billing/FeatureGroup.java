package me.nageshk.omnichannel.db.billing;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/** Authorities that are sold together, such as the CRM. An authority is in at most one group. */
@Document("featureGroups")
@Getter
@Setter
public class FeatureGroup {

    @Id
    private String code;

    private String name;
    private String description;
    private GroupCategory category;
    private Set<String> authorities = new HashSet<>();
    /** Codes of the groups that must be present together with this one. */
    private Set<String> requires = new HashSet<>();
    /**
     * The built-in authorities this group has been given so far (null on a group stored before this was recorded): a
     * later release adds only the ones not in here, so what the staff removed stays removed.
     */
    private Set<String> releasedAuthorities;
    private boolean builtIn;
    private int sortOrder;
    private Instant createdAt;
    private String createdBy;
    private Instant updatedAt;
    private String updatedBy;

    @Version
    private Long version;
}
