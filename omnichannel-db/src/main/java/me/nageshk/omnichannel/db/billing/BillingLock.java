package me.nageshk.omnichannel.db.billing;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** The lease that makes billing changes to one customer happen one after another; its id is the customer's. */
@Document("billingLocks")
@Getter
@Setter
public class BillingLock {

    @Id
    private String id;
    private String holder;
    private Instant lockedUntil;
}
