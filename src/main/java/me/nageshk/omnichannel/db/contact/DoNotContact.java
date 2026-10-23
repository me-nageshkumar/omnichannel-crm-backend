package me.nageshk.omnichannel.db.contact;

import java.time.Instant;

/** A contact's "do not contact" mark: when it was set, why, and the campaign whose STOP reply set it. */
public record DoNotContact(Instant at, Reason reason, String campaignId) {

    public enum Reason { STOP_REPLY, MANUAL }
}
