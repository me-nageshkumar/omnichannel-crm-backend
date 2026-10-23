package me.nageshk.omnichannel.crm.common;

import org.springframework.data.mongodb.core.mapping.Field;

/** The CRM record (or settings/pipeline) something happened to. */
public record CrmTarget(TargetType type, @Field("id") String id) {}
