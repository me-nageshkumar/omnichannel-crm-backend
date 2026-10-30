package me.nageshk.omnichannel.crm.report.dto;

import java.time.Instant;
import java.util.List;

/** Spec C13: the brand's campaigns started in the period, with delivery and how many CRM contacts they reached. */
public record CampaignsView(List<Row> rows, boolean truncated) {

    public record Row(String campaignId, String name, String status, Instant startedAt, List<Number> numbers,
            long recipients, long sent, long delivered, long read, long replied, long failed,
            long crmContactsReached) {}

    public record Number(String phoneNumberId, String label) {}
}
