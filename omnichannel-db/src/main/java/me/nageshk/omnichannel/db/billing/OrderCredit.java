package me.nageshk.omnichannel.db.billing;

import java.math.BigDecimal;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * Value an order gives back on one earlier invoice; it becomes a credit note when the order is activated.
 *
 * @param creditNoteId reserved when the order is raised, so issuing the note can be repeated safely
 */
public record OrderCredit(String creditNoteId, String invoiceId, String invoiceNumber, String description,
        @Field(targetType = FieldType.DECIMAL128) BigDecimal taxable, TaxAmounts tax, @Field(targetType = FieldType.DECIMAL128) BigDecimal total) {}
