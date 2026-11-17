package me.nageshk.omnichannel.db.billing;

import java.math.BigDecimal;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/** GST on one document: CGST and SGST within a state, IGST between states. */
public record TaxAmounts(@Field(targetType = FieldType.DECIMAL128) BigDecimal ratePercent, @Field(targetType = FieldType.DECIMAL128) BigDecimal cgst, @Field(targetType = FieldType.DECIMAL128) BigDecimal sgst, @Field(targetType = FieldType.DECIMAL128) BigDecimal igst, boolean interState) {

    public BigDecimal total() {
        return cgst.add(sgst).add(igst);
    }
}
