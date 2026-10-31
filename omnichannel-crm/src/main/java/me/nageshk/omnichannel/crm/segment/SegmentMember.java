package me.nageshk.omnichannel.crm.segment;

/** One CRM contact record in a segment: the record, the host contact behind it and the channel account it is on. */
public record SegmentMember(String crmContactId, String contactId, String channelAccountId) {}
