package me.nageshk.omnichannel.inbox;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/** A message in a chat, in either direction. */
@Document("wa_web_messages")
@CompoundIndexes({
        @CompoundIndex(name = "phone_wamessage_unique", def = "{'phoneNumberId': 1, 'waMessageId': 1}", unique = true),
        @CompoundIndex(name = "chat_timestamp", def = "{'chatId': 1, 'timestamp': -1, '_id': -1}")
})
@Getter
@Setter
public class WaWebMessage {

    @Id
    private String id;
    private String customerId;
    private String workspaceId;
    private String phoneNumberId;
    private String chatId;
    /** {@code id._serialized} from whatsapp-web.js. */
    private String waMessageId;
    private MessageDirection direction;
    private MessageSource source;
    /** The whatsapp-web.js type string, kept verbatim. */
    private String type;
    /** Text, the caption of a media message, or the vCard text. */
    private String body;
    private Media media;
    private Location location;
    /** WhatsApp's message time. */
    private Instant timestamp;
    /** The highest status reached. */
    private MessageStatus status;
    private StatusTimes statusTimes;
    /** The raw whatsapp-web.js ack last seen. */
    private Integer ack;
    private String sentByUserId;
    private String sentByName;
    private String templateId;
    @CreatedDate
    private Instant creationTime;

    /** {@code mediaId} is the chat_media GridFS id once the bytes are fetched, else null. */
    public record Media(String mimetype, String filename, Long size, String mediaId, Instant downloadedAt) {
    }

    public record Location(Double latitude, Double longitude, String description) {
    }

    /** Each time is set once, when the status is first reached. */
    @Getter
    @Setter
    public static class StatusTimes {
        private Instant sentAt;
        private Instant deliveredAt;
        private Instant readAt;
        private Instant playedAt;
        private Instant failedAt;
    }
}
