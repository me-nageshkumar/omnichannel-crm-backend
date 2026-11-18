package me.nageshk.omnichannel.service.phone.move;

import java.time.Instant;
import me.nageshk.omnichannel.service.phone.move.PhoneNumberMove.Counts;
import me.nageshk.omnichannel.service.phone.move.PhoneNumberMove.Step;

/** A move as the console shows it: the brands by name, how far it got, and whether it finished. */
public record PhoneNumberMoveView(String id, String phoneNumberId, String fromWorkspaceId, String fromWorkspaceName,
        String toWorkspaceId, String toWorkspaceName, Step step, boolean done, String error, Counts counts,
        Instant startedAt, Instant finishedAt) {
}
