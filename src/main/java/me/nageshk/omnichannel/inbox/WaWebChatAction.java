package me.nageshk.omnichannel.inbox;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** One entry in a chat's audit trail, shown in the thread as a system line. */
@Document("waWebChatActions")
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
    /** Only on {@code ROUTED}: why routing chose as it did when it was not the plain setting. */
    private RoutedReason reason;
    /** Only on {@code ROUTED} with {@link RoutedReason#HANDED_BACK}/{@code MEMBER_REMOVED}: who was skipped. */
    private String excludedUserId;
    /** Only on {@code TRANSFERRED}: the team the chat left. */
    private String fromTeamId;
    private String fromTeamName;
}
