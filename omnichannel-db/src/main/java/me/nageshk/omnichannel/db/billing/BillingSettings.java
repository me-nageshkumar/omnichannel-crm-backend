package me.nageshk.omnichannel.db.billing;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/** The platform's billing settings: one document. */
@Document("billingSettings")
@Getter
@Setter
public class BillingSettings {

    public static final String ID = "billing";

    @Id
    private String id = ID;

    /** Days begin and end in this zone. */
    private String zone = "Asia/Kolkata";
    /** Days of full use after a term ends, before the account turns read-only. */
    private int graceDays = 7;
    private WithoutPlan customersWithoutPlan = WithoutPlan.UNRESTRICTED;

    @Version
    private Long version;
}
