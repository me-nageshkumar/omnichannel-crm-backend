package me.nageshk.omnichannel.db.automation;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** A queued event waiting for the worker; moves to the history when done. */
@Document("automationEvents")
@CompoundIndex(name = "status_created", def = "{'status': 1, 'createdAt': 1}")
@Getter
@Setter
public class AutomationEvent {

    @Id
    private String id;
    private String customerId;
    private String workspaceId;
    private AutomationEventKind kind;
    private EventData data;
    private String contactId;
    private String contactPhone;
    private EventStatus status;
    private int attempts;
    private Instant leaseUntil;
    private Instant notBefore;
    private String lastError;
    private Instant createdAt;
}
