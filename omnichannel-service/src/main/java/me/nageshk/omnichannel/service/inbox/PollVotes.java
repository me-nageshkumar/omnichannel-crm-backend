package me.nageshk.omnichannel.service.inbox;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import me.nageshk.omnichannel.db.inbox.WaWebMessage.PollVote;
import tools.jackson.databind.JsonNode;

/** The votes on a poll: one entry per voter, holding their latest choice. */
public final class PollVotes {

    private PollVotes() {
    }

    /** The vote in a {@code vote_update} payload, or in a fetched poll's list; null without a voter. */
    public static PollVote parse(JsonNode vote) {
        String voter = vote.path("voter").isString() ? vote.path("voter").asString() : "";
        if (voter.isEmpty()) return null;
        List<Integer> options = new ArrayList<>();
        vote.path("options").forEach(o -> options.add(o.asInt()));
        return new PollVote(voter, vote.path("fromMe").asBoolean(false), options,
                Instant.ofEpochMilli(vote.path("at").asLong(0)));
    }

    /**
     * {@code current} with the vote applied; {@code current} itself when the vote changes nothing, because it is the
     * same or older than what the voter has. Taking back every option removes the voter.
     */
    static List<PollVote> merged(List<PollVote> current, PollVote vote) {
        PollVote known = current == null ? null
                : current.stream().filter(v -> v.voter().equals(vote.voter())).findFirst().orElse(null);
        if (known != null && (known.equals(vote) || known.at().isAfter(vote.at()))) return current;
        if (known == null && vote.options().isEmpty()) return current;
        List<PollVote> next = new ArrayList<>();
        if (current != null) current.stream().filter(v -> !v.voter().equals(vote.voter())).forEach(next::add);
        if (!vote.options().isEmpty()) next.add(vote);
        return next;
    }
}
