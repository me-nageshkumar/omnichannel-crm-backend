package me.nageshk.omnichannel.db.billing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/** Account credit paid out to a customer, recorded by staff after the money left outside the app. */
@Document("refunds")
@Getter
@Setter
public class Refund {

    @Id
    private String id;

    @Indexed(unique = true)
    private String number;

    @Indexed
    private String customerId;

    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal amount;
    private String method;
    private String reference;
    private LocalDate paidOn;
    private String note;
    private String recordedBy;
    private Instant recordedAt;
    private RefundStatus status;
}
