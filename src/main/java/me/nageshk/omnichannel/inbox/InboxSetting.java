package me.nageshk.omnichannel.inbox;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** Routing settings of one phone number; without a document the mode is NONE. */
@Document("inboxSettings")
@CompoundIndex(name = "phone_unique", def = "{'phoneNumberId': 1}", unique = true)
@Getter
@Setter
public class InboxSetting {

    @Id
    private String id;
    private String customerId;
    private String workspaceId;
    private String phoneNumberId;
    private Routing routing = new Routing(RoutingMode.NONE, null, null);
    /** Replying to an unassigned chat assigns it to the replier. */
    private boolean claimOnReply = true;
    /** Keep the assignment when a chat is closed. */
    private boolean keepAssignmentOnClose = false;
    /** User id last assigned by round robin. */
    private String roundRobinCursor;

    public record Routing(RoutingMode mode, String teamId, String userId) {
    }
}
