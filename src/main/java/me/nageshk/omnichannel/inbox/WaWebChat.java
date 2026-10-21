package me.nageshk.omnichannel.inbox;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/** One conversation between a WhatsApp Web number and a remote chat. */
@Document("wa_web_chats")
@CompoundIndexes({
        @CompoundIndex(name = "phone_remote_unique", def = "{'phoneNumberId': 1, 'remoteId': 1}", unique = true),
        @CompoundIndex(name = "phone_last_message", def = "{'phoneNumberId': 1, 'lastMessageAt': -1, '_id': -1}"),
        @CompoundIndex(name = "phone_user_last_message",
                def = "{'phoneNumberId': 1, 'assignedUserId': 1, 'lastMessageAt': -1, '_id': -1}"),
        @CompoundIndex(name = "phone_team_last_message",
                def = "{'phoneNumberId': 1, 'assignedTeamId': 1, 'lastMessageAt': -1, '_id': -1}"),
        @CompoundIndex(name = "phone_contact", def = "{'phoneNumberId': 1, 'contactId': 1}"),
        @CompoundIndex(name = "workspace_contact", def = "{'workspaceId': 1, 'contactId': 1}")
})
@Getter
@Setter
public class WaWebChat {

    @Id
    private String id;
    private String customerId;
    private String workspaceId;
    private String phoneNumberId;
    /** WhatsApp chat id, {@code <digits>@c.us} or {@code <id>@lid}. */
    private String remoteId;
    private String contactId;
    private ChatStatus status;
    private String assignedUserId;
    private String assignedTeamId;
    /** Set whenever the assignment changes. */
    private Instant assignedAt;
    /** Inbound messages since the chat was last opened in the console. */
    private int unreadCount;
    private Instant lastMessageAt;
    private LastMessage lastMessage;
    private Instant lastInboundAt;
    private Instant lastOutboundAt;
    @CreatedDate
    private Instant creationTime;
    @LastModifiedDate
    private Instant lastUpdateTime;
    @Version
    private Long version;

    /** Preview of the newest message; {@code text} is the first 120 characters of the body or a type label. */
    public record LastMessage(MessageDirection direction, String type, String text, MessageStatus status) {
    }
}
