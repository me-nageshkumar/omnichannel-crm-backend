package me.nageshk.omnichannel.inbox;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** One entry in a chat's audit trail, shown in the thread as a system line. */
@Document("wa_web_chat_actions")
@CompoundIndex(name = "chat_at", def = "{'chatId': 1, 'at': -1, '_id': -1}")
@Getter
@Setter
public class WaWebChatAction {

    @Id
    private String id;
    private String customerId;
    private String workspaceId;
    private String chatId;
    private ActionType type;
    /** Null for automatic routing, shown as "System". */
    private String byUserId;
    private String byName;
    private String toUserId;
    private String toUserName;
    private String toTeamId;
    private String toTeamName;
    private Instant at;
}
