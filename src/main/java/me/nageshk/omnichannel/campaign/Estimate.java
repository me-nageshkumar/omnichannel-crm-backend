package me.nageshk.omnichannel.campaign;

import java.time.LocalDate;

/** How long the remaining messages take at the chosen senders' pace. */
public record Estimate(int remaining, int perDay, Integer days, LocalDate finishBy) {
}
