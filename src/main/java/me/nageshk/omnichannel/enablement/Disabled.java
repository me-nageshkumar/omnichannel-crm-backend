package me.nageshk.omnichannel.enablement;

import java.time.Instant;

/** Why a thing is disabled: who did it, at which level, and when. Null on the thing means enabled. */
public record Disabled(String by, EnableLevel level, Instant at) {}
