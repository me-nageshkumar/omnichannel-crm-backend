package me.nageshk.omnichannel.crm.platform;

import java.time.Instant;

/** An existing contact note, shown on the CRM contact's timeline. */
public record PlatformNote(String id, String text, String createdByName, Instant at) {}
