package me.nageshk.omnichannel.db.tenant;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import me.nageshk.omnichannel.db.enablement.Disabled;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** A brand inside a customer. */
@Document("workspaces")
@CompoundIndex(name = "customer_name_unique", def = "{'customerId': 1, 'name': 1}", unique = true)
@Getter
@Setter
public class Workspace {

    @Id
    private String id;

    private String customerId;
    private String name;
    private Disabled disabled;

    @CreatedDate
    private Instant creationTime;

    @LastModifiedDate
    private Instant lastUpdateTime;
}
