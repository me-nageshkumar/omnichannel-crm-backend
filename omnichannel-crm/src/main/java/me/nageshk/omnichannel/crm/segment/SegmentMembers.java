package me.nageshk.omnichannel.crm.segment;

import java.util.List;

/**
 * A resolved segment (spec D C5, C20): the member records in {@code crmContactId} order, capped at 50,000
 * ({@code truncated} when cut), {@code total} = matching records, {@code people} = distinct host contacts.
 */
public record SegmentMembers(List<SegmentMember> members, long total, long people, boolean truncated) {

    public static final int MAX_MEMBERS = 50_000;
}
