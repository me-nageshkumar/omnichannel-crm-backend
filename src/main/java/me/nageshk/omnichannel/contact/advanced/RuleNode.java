package me.nageshk.omnichannel.contact.advanced;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonSubTypes.Type;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** A node of the filter rule tree: a {@link Rule} or a {@link RuleGroup}, told apart by its properties. */
@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
@JsonSubTypes({@Type(Rule.class), @Type(RuleGroup.class)})
public sealed interface RuleNode permits Rule, RuleGroup {}
