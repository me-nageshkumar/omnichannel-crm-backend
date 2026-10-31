package me.nageshk.omnichannel.crm.segment;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonSubTypes.Type;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * A node of a segment's rule tree: a {@link SegmentRule} or a {@link SegmentRuleGroup}, told apart by their
 * properties. The JSON shape is the host's advanced contact filter's, so the console reuses its tree helpers.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
@JsonSubTypes({@Type(SegmentRule.class), @Type(SegmentRuleGroup.class)})
public sealed interface SegmentRuleNode permits SegmentRule, SegmentRuleGroup {}
