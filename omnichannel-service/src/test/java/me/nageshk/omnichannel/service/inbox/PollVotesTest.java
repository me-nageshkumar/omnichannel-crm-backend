package me.nageshk.omnichannel.service.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import me.nageshk.omnichannel.db.inbox.WaWebMessage.PollVote;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class PollVotesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static PollVote vote(String voter, long at, Integer... options) {
        return new PollVote(voter, false, List.of(options), Instant.ofEpochMilli(at));
    }

    private static ObjectNode payload(String voter, long at) {
        ObjectNode p = MAPPER.createObjectNode().put("pollMessageId", "poll-1").put("fromMe", true).put("at", at);
        if (voter != null) p.put("voter", voter);
        p.putArray("options").add(2).add(0);
        return p;
    }

    @Test
    void parseReadsTheVote() {
        assertThat(PollVotes.parse(payload("1@lid", 5000)))
                .isEqualTo(new PollVote("1@lid", true, List.of(2, 0), Instant.ofEpochMilli(5000)));
    }

    @Test
    void aVoteWithoutAVoterIsNothing() {
        assertThat(PollVotes.parse(payload(null, 5000))).isNull();
    }

    @Test
    void aFirstVoteIsAdded() {
        assertThat(PollVotes.merged(null, vote("a", 1, 0))).containsExactly(vote("a", 1, 0));
    }

    @Test
    void aLaterVoteReplacesTheVotersChoice() {
        assertThat(PollVotes.merged(List.of(vote("a", 1, 0), vote("b", 1, 1)), vote("a", 2, 2)))
                .containsExactlyInAnyOrder(vote("a", 2, 2), vote("b", 1, 1));
    }

    @Test
    void anOlderVoteChangesNothing() {
        List<PollVote> current = List.of(vote("a", 5, 0));
        assertThat(PollVotes.merged(current, vote("a", 2, 1))).isSameAs(current);
    }

    @Test
    void theSameVoteChangesNothing() {
        List<PollVote> current = List.of(vote("a", 5, 0));
        assertThat(PollVotes.merged(current, vote("a", 5, 0))).isSameAs(current);
    }

    @Test
    void takingBackEveryOptionRemovesTheVoter() {
        assertThat(PollVotes.merged(List.of(vote("a", 1, 0), vote("b", 1, 1)), vote("a", 2)))
                .containsExactly(vote("b", 1, 1));
    }

    @Test
    void takingBackWithoutAVoteChangesNothing() {
        assertThat(PollVotes.merged(null, vote("a", 2))).isNull();
    }
}
