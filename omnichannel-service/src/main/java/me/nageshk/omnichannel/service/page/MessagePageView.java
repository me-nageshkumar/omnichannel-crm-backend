package me.nageshk.omnichannel.service.page;

import java.time.Instant;
import java.util.List;

/** What the customer did on a message page, as the console shows it under the message. */
public record MessagePageView(String url, Instant expiresAt, Instant openedAt, int openCount, List<Count> buttons,
        List<Count> rows, Count cta, Location location) {

    /** Taps of one button, row or link; {@code replied} once the customer's WhatsApp reply was matched to it. */
    public record Count(String id, String title, int count, boolean replied) {}

    public record Location(double latitude, double longitude, Instant at) {}
}
