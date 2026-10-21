package me.nageshk.omnichannel.campaign;

/** With an {@code action} the sender changes state; without one {@code runsOn} is replaced. */
public record SenderUpdate(String runsOn, SenderAction action) {
}
