package me.nageshk.omnichannel.crm.segment.dto;

import java.util.List;
import me.nageshk.omnichannel.crm.platform.ChannelKind;

/** Who matches, for the caller: records, people, the cap, and records per channel account (spec D C6, C20). */
public record SegmentPreviewView(long total, long people, boolean truncated, List<ChannelAccountCount> channelAccounts) {

    public record ChannelAccountCount(String channelAccountId, ChannelKind kind, String label, long count) {}
}
