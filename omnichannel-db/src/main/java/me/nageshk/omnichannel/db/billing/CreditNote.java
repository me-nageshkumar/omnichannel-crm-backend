package me.nageshk.omnichannel.db.billing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * Gives value back on one invoice, with that invoice's tax. Never changed after it is issued; numbered in its own
 * series, without gaps within a financial year.
 */
@Document("creditNotes")
@CompoundIndex(name = "year_sequence", def = "{'financialYear': 1, 'sequence': 1}", unique = true)
@CompoundIndex(name = "customer_issuedAt", def = "{'customerId': 1, 'issuedAt': -1}")
@Getter
@Setter
public class CreditNote {

    @Id
    private String id;

    private String number;
    private String financialYear;
    private int sequence;
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

    private String invoiceId;
    private String invoiceNumber;
    private Instant invoiceDate;
    private String reason;
    private String pdfFileId;
}
