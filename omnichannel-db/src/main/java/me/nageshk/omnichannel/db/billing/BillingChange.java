package me.nageshk.omnichannel.db.billing;

import java.time.Instant;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** One change to a feature group, a plan, a customer's subscription or the billing settings. Kept for good. */
@Document("billingChanges")
@CompoundIndex(name = "target_at", def = "{'targetType': 1, 'targetId': 1, 'at': -1}")
@Getter
@Setter
public class BillingChange {

    public static final String GROUP = "GROUP";
    public static final String PLAN = "PLAN";
    public static final String SUBSCRIPTION = "SUBSCRIPTION";
    public static final String SETTINGS = "SETTINGS";

    @Id
    private String id;

    private String targetType;
    private String targetId;
    private String kind;
    private String summary;
    private Map<String, Object> before;
    private Map<String, Object> after;
    private String by;
    private Instant at;
}
