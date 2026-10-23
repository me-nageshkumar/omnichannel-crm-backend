package me.nageshk.omnichannel.crm.activity.dto;

import java.time.Instant;
import java.util.Map;
import me.nageshk.omnichannel.crm.common.CrmTarget;
import me.nageshk.omnichannel.crm.common.Ref;

/** One timeline entry; {@code actor} is null for SYSTEM entries; legacy notes have the type {@code LEGACY_NOTE}. */
public record ActivityView(String id, String type, CrmTarget target, String summary, Map<String, Object> payload,
        Ref actor, String actorType, Instant happenedAt) {}
