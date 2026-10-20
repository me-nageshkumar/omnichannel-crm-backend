package me.nageshk.omnichannel.tenant;

import me.nageshk.omnichannel.enablement.Disabled;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

/** A company that uses the platform. Owns workspaces (brands), which own teams. */
@Document("customers")
@Getter
@Setter
public class Customer {

    @Id
    private String id;

    private String name;
    private boolean active;
    private Disabled disabled;
    private String createdBy;

    @CreatedDate
    private Instant creationTime;

    @LastModifiedDate
    private Instant lastUpdateTime;
}
