package me.nageshk.omnichannel.db.billing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * A tax invoice, issued when an order is paid and never changed afterwards: it holds its own copy of both parties
 * and every amount. Numbers run without gaps within a financial year. Only {@link #credited}, the credit notes
 * against it and the stored PDF's id are ever updated.
 */
@Document("invoices")
@CompoundIndex(name = "year_sequence", def = "{'financialYear': 1, 'sequence': 1}", unique = true)
@CompoundIndex(name = "customer_issuedAt", def = "{'customerId': 1, 'issuedAt': -1}")
@Getter
@Setter
public class Invoice {

    @Id
    private String id;

    private String number;
    private String financialYear;
    private int sequence;
    @Indexed(unique = true)
    private String orderId;
    private String orderNumber;
    private String customerId;
    private Instant issuedAt;
    private DocumentParty supplier;
    private DocumentParty customer;
    private String sacCode;
    private List<DocumentLine> lines = new ArrayList<>();

    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal taxable = BigDecimal.ZERO;
    private TaxAmounts tax;
    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal total = BigDecimal.ZERO;
    private String totalInWords;

    /** Account credit set against the total; the rest was paid. */
    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal accountCreditUsed = BigDecimal.ZERO;
    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal amountPaid = BigDecimal.ZERO;
    private OrderPayment payment;

    /** The taxable value credit notes have given back on this invoice so far; never more than {@link #taxable}. */
    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal credited = BigDecimal.ZERO;
    private Set<String> creditNoteIds = new HashSet<>();
    private String pdfFileId;
}
