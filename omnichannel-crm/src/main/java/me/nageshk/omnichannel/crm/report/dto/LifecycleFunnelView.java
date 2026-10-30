package me.nageshk.omnichannel.crm.report.dto;

import java.util.List;

/** Spec C10: contacts per lifecycle status now, in picklist order; the date range does not apply. */
public record LifecycleFunnelView(List<Row> rows, long total) {

    public record Row(String key, String label, boolean active, long count) {}
}
