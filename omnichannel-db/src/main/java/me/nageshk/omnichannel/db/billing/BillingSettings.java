package me.nageshk.omnichannel.db.billing;

import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

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

    /** The supplier as printed on invoices. */
    private String supplierLegalName;
    private String supplierAddress;
    private String supplierStateCode;
    private String supplierGstin;
    /** The HSN/SAC code of the service sold. */
    private String sacCode;

    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal gstRatePercent = new BigDecimal("18");
    private String invoicePrefix = "INV";
    private String creditNotePrefix = "CN";
    /** Days a quote stays payable. */
    private int quoteValidDays = 7;
    /** Days before a term ends from which it can be renewed. */
    private int renewalWindowDays = 30;
    /** How a customer pays a request (bank account, UPI id); shown to it beside an open request. */
    private String paymentInstructions;

    /** Whether an invoice can be issued: the supplier's name, address and state are set. */
    public boolean supplierComplete() {
        return filled(supplierLegalName) && filled(supplierAddress) && filled(supplierStateCode);
    }

    private static boolean filled(String s) {
        return s != null && !s.isBlank();
    }

    @Version
    private Long version;
}
