package me.nageshk.omnichannel.crm.segment;

import java.util.List;
import java.util.Map;

/**
 * A segment resolved for a campaign (spec D C12, C20, C21): the distinct host contact ids in member order, each
 * contact's channel account (its first member's), and the record counts and cap of the resolution.
 */
public record AudienceMembers(List<String> contactIds, Map<String, String> channelAccountByContactId, long total,
        long people, boolean truncated) {}
