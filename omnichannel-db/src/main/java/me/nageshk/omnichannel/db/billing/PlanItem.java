package me.nageshk.omnichannel.db.billing;

import java.math.BigDecimal;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/** A feature group with its price for one period of the plan, before tax. */
public record PlanItem(String groupCode, @Field(targetType = FieldType.DECIMAL128) BigDecimal price) {}
