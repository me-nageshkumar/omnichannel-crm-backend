package me.nageshk.omnichannel.tenant;

import java.time.Instant;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** A brand inside a customer. */
@Document("workspaces")
@CompoundIndex(name = "customer_name_unique", def = "{'customerId': 1, 'name': 1}", unique = true)
public class Workspace {

    @Id
    private String id;

    private String customerId;
    private String name;

    @CreatedDate
    private Instant creationTime;

    @LastModifiedDate
    private Instant lastUpdateTime;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Instant getCreationTime() { return creationTime; }
    public void setCreationTime(Instant creationTime) { this.creationTime = creationTime; }
    public Instant getLastUpdateTime() { return lastUpdateTime; }
    public void setLastUpdateTime(Instant lastUpdateTime) { this.lastUpdateTime = lastUpdateTime; }
}
