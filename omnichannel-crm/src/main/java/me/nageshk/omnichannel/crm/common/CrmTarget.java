package me.nageshk.omnichannel.crm.common;

/** The CRM record (or settings/pipeline) something happened to. */
public record CrmTarget(TargetType type, String id) {}
