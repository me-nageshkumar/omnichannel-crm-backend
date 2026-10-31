package me.nageshk.omnichannel.crm.platform;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** A brand campaign as the CRM's campaigns card sees it (spec C13). */
public record PlatformCampaign(String id, String name, String status, Instant startedAt, List<String> phoneNumberIds,
        Counts counts, Set<String> recipientContactIds, String segmentId, String segmentName) {

    public record Counts(int total, int sent, int delivered, int read, int replied, int failed) {}
}
