package me.nageshk.omnichannel.db.billing;

import java.time.Instant;
import java.time.LocalDate;

/** How an order was paid, as staff recorded it. {@code method} is NONE for an order with nothing to pay. */
public record OrderPayment(String method, String reference, LocalDate receivedOn, String note, String recordedBy,
        Instant recordedAt) {

    public static final String NONE = "NONE";
}
