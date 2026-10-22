package me.nageshk.omnichannel.inbox;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * How a team's new chats are routed and what happens to the assignment on reply and close. One document per
 * scope: {@code teamId} null is the brand default (always present once anyone asked for it), a non-null
 * {@code teamId} is that team's override (design §3.4). The owning team of a chat's number decides which applies.
 *
 * <p>The unique index {@code workspace_team_unique} on {@code (workspaceId, teamId)} is created by
 * {@link InboxSettingsMigration}, not by annotation: the per-number documents it moves away would violate it.
 */
@Document("inboxSettings")
@Getter
@Setter
public class InboxSetting {

    public static final int DEFAULT_STICKY_DAYS = 30;
    public static final int MAX_STICKY_DAYS = 365;

    @Id
    private String id;
    private String customerId;
    private String workspaceId;
    /** Null for the brand default. */
    private String teamId;
    private Routing routing = new Routing(RoutingMode.TEAM, null);
    /** How long a returning customer goes back to the agent who last handled them; 0 turns it off. */
    private int stickyDays = DEFAULT_STICKY_DAYS;
    /** Replying to an unassigned chat assigns it to the replier. */
    private boolean claimOnReply = true;
    /** Keep the assignment when a chat is closed. */
    private boolean keepAssignmentOnClose = false;

    /** The team is always the owning (or target) team; {@code userId} only with {@link RoutingMode#USER}. */
    public record Routing(RoutingMode mode, String userId) {
    }

    /** Copies everything but identity and scope into a new document for {@code teamId}. */
    InboxSetting copyFor(String teamId) {
        InboxSetting s = new InboxSetting();
        s.setCustomerId(customerId);
        s.setWorkspaceId(workspaceId);
        s.setTeamId(teamId);
        s.setRouting(routing);
        s.setStickyDays(stickyDays);
        s.setClaimOnReply(claimOnReply);
        s.setKeepAssignmentOnClose(keepAssignmentOnClose);
        return s;
    }
}
