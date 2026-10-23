package me.nageshk.omnichannel.db.enablement;

import java.time.Instant;
import me.nageshk.omnichannel.common.enablement.EnableLevel;

/** Why a thing is disabled: who did it, at which level, and when. Null on the thing means enabled. */
public record Disabled(String by, EnableLevel level, Instant at) {}
