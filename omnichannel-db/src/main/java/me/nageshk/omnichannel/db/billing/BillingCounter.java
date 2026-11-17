package me.nageshk.omnichannel.db.billing;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** The last number given out in a series whose numbers may have gaps (orders, refunds). */
@Document("billingCounters")
@Getter
@Setter
public class BillingCounter {

    @Id
    private String id;
    private long value;
}
