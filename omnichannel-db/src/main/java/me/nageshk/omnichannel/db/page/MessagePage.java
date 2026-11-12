package me.nageshk.omnichannel.db.page;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A Cloud interactive message sent over the bridge as a link: the page behind the link, what it shows, and what the
 * customer did on it. {@code message} is the Cloud API object as sent (snake_case keys inside).
 */
@Document("messagePages")
@CompoundIndex(name = "chat_created", def = "{'chatId': 1, 'createdAt': -1}")
@Getter
@Setter
public class MessagePage {

    public enum ActionKind { OPEN, BUTTON_REPLY, LIST_REPLY, CTA_URL, LOCATION }

    /**
     * One thing the customer did. {@code id} is the reply or row id; {@code repliedMessageId} the inbound message that
     * answered a reply or location request, once matched.
     */
    public record Action(ActionKind kind, String id, String title, Double latitude, Double longitude, Instant at,
            String uaHash, String repliedMessageId) {

        public Action withReplied(String messageId) {
            return new Action(kind, id, title, latitude, longitude, at, uaHash, messageId);
        }
    }

    @Id
    private String id;
    private String workspaceId;
    private String customerId;
    /** 22 url-safe characters of 16 random bytes; the whole secret of the link. */
    @Indexed(unique = true)
    private String token;
    private String chatId;
    private String contactId;
    private String phoneNumberId;
    /** The sending number without {@code +}, for the wa.me reply links. */
    private String senderDigits;
    /** The stored outbound message, once the device accepted the send. */
    @Indexed(sparse = true)
    private String messageId;
    private Map<String, Object> message;
    private Instant createdAt;
    private Instant expiresAt;
    private Instant openedAt;
    private int openCount;
    private Instant lastActionAt;
    private List<Action> actions = new ArrayList<>();
    private boolean previewReady;
    private String previewFileId;
}
