package me.nageshk.omnichannel.db.billing;

import java.math.BigDecimal;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/** One line of an invoice or credit note, before tax. */
public record DocumentLine(String description, @Field(targetType = FieldType.DECIMAL128) BigDecimal amount) {}
