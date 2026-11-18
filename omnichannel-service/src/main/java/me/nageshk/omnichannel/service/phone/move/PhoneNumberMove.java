package me.nageshk.omnichannel.service.phone.move;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One move of a number to another brand of its customer (spec §7.2), kept after it finishes. {@code step} is the last
 * step done, so a move that stopped half way is finished by running the steps after it again.
 */
@Document("phoneNumberMoves")
@Getter
@Setter
public class PhoneNumberMove {

    /** The steps in the order they are done; each is recorded once its work is done. */
    public enum Step { STARTED, OFFLINE, RUNS_ENDED, ARCHIVED, MERGED, DONE }

    /** What the move carried: chats and messages left on the source archive, chatbot runs it ended. */
    public record Counts(long chats, long messages, long runsEnded) {
    }

    @Id
    private String id;
    private String customerId;
    @Indexed
    private String phoneNumberId;
    /**
     * The number's id while the move is unfinished, absent once it is done: the unique sparse index allows one
     * unfinished move per number.
     */
    @Indexed(unique = true, sparse = true, name = "unfinished_unique")
    private String unfinishedOf;
    private String fromWorkspaceId;
    private String toWorkspaceId;
    /** The archive left in the source brand; chosen before it is created, so a retry never makes a second one. */
    private String sourceArchiveId;
    /** The target brand's archive of this number that the move merged back into the number, if there was one. */
    private String mergedArchiveId;
    private Step step;
    private Counts counts;
    private Instant startedAt;
    private Instant finishedAt;
    private String byUserId;
    /** Why the last attempt stopped; cleared when Retry starts. */
    private String error;
    @Version
    private Long version;
}
