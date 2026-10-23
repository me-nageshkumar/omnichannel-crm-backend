package me.nageshk.omnichannel.crm.contact.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.crm.common.Ref;
import me.nageshk.omnichannel.crm.contact.Channel;
import me.nageshk.omnichannel.crm.contact.OriginKind;

public record CrmContactView(String id, String contactId, Channel channel, String phone, String name,
        String firstName, String lastName, String email, String jobTitle, Ref company, Ref owner,
        String lifecycleStatus, String source, OriginView origin, Map<String, Object> customFields,
        int openDealCount, BigDecimal openDealAmount, Instant lastActivityAt, Instant nextTaskDueAt,
        boolean contactDeleted, List<ChannelChangeView> channelHistory, Instant creationTime, Long version) {

    public record OriginView(OriginKind kind, String phoneNumberId, String chatId, Instant firstMessageAt,
            Ref capturedBy) {}

    public record ChannelChangeView(String from, String to, Ref by, Instant at, String reason) {}
}
