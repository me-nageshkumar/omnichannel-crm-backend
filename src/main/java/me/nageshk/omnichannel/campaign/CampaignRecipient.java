package me.nageshk.omnichannel.campaign;

import java.time.Instant;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/** One row of a campaign's audience and what became of it. */
@Document("campaign_recipients")
@CompoundIndexes({
        @CompoundIndex(name = "campaign_phone", def = "{'campaignId': 1, 'phone': 1}", unique = true,
                partialFilter = "{'phone': {$type: 'string'}}"),
        @CompoundIndex(name = "campaign_status", def = "{'campaignId': 1, 'status': 1, '_id': 1}"),
        @CompoundIndex(name = "ws_phone_sent", def = "{'workspaceId': 1, 'phone': 1, 'sentAt': 1}"),
        @CompoundIndex(name = "sender_sent", def = "{'senderId': 1, 'sentAt': 1}"),
        @CompoundIndex(name = "sender_attempted", def = "{'senderId': 1, 'attemptedAt': 1}"),
        @CompoundIndex(name = "message", def = "{'messageId': 1}"),
        @CompoundIndex(name = "chat_sent", def = "{'chatId': 1, 'sentAt': -1}")
})
@Getter
@Setter
public class CampaignRecipient {

    @Id
    private String id;
    private String campaignId;
    private String workspaceId;
    private String phone;
    private String rawPhone;
    private String contactId;
    private String templateId;
    private List<String> values;
    private RecipientStatus status;
    private SkipReason skipReason;
    private String preferredSenderId;
    /** The id of the phone number that sent, or tried to send, the message. */
    private String senderId;
    private String chatId;
    private String messageId;
    private Instant attemptedAt;
    private Instant sentAt;
    private Instant deliveredAt;
    private Instant readAt;
    private Instant repliedAt;
    private String error;
    private int attempts;
}
