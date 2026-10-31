package me.nageshk.omnichannel.crm.platform;

import java.util.List;

/** What a channel contact carries on its channel, named for display; both lists are sorted by name. */
public record ChannelContactDetails(List<ChannelLabel> labels, List<ChannelAttributeValue> attributes) {}
