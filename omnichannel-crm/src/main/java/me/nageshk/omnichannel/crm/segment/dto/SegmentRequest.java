package me.nageshk.omnichannel.crm.segment.dto;

import me.nageshk.omnichannel.crm.segment.SegmentRuleGroup;

/** Body of create and update; {@code version} is required on update. */
public record SegmentRequest(String name, String description, SegmentRuleGroup rules, Long version) {}
