package me.nageshk.omnichannel.db.billing;

import java.math.BigDecimal;
import me.nageshk.omnichannel.common.billing.LimitKey;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/** Extra room under a limit: {@code unit} more for {@code unitPrice} per period. */
public record LimitAddOn(LimitKey key, @Field(targetType = FieldType.DECIMAL128) BigDecimal unitPrice, int unit) {}
