package me.nageshk.omnichannel.db.billing;

import java.math.BigDecimal;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;
import me.nageshk.omnichannel.common.billing.LimitKey;

/**
 * One charge of an order.
 *
 * @param type PLAN, ADDON or LIMIT_ADDON
 * @param listAmount the price before the plan's discount and any extra discount
 * @param amount what is charged, before tax
 */
public record OrderLine(String type, String description, String groupCode, LimitKey limitKey, int quantity,
        @Field(targetType = FieldType.DECIMAL128) BigDecimal listAmount, @Field(targetType = FieldType.DECIMAL128) BigDecimal amount) {

    public static final String PLAN = "PLAN";
    public static final String ADDON = "ADDON";
    public static final String LIMIT_ADDON = "LIMIT_ADDON";
}
