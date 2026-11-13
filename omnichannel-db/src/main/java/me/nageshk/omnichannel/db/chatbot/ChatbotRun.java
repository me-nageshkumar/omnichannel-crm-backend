package me.nageshk.omnichannel.db.chatbot;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One run of a chatbot for one person (spec §5.1). The contact is its identity; the chat and number are its WhatsApp
 * context, filled when a node needs them. A waiting run says what it waits for in {@code waiting}; answer sources find
 * it by {@code (waiting.kind, waiting.key)}. One active run per contact per brand, one live run per (event, rule).
 */
@Document("chatbotRuns")
@CompoundIndexes({
        @CompoundIndex(name = "waiting_kind_key_status", def = "{'waiting.kind': 1, 'waiting.key': 1, 'status': 1}"),
        @CompoundIndex(name = "chat_status", def = "{'chatId': 1, 'status': 1}"),
        @CompoundIndex(name = "ws_chatbot_created", def = "{'workspaceId': 1, 'chatbotId': 1, 'createdAt': -1}"),
        @CompoundIndex(name = "status_end", def = "{'status': 1, 'endAt': 1}"),
        @CompoundIndex(name = "status_remind", def = "{'status': 1, 'remindAt': 1}"),
        @CompoundIndex(name = "status_lease", def = "{'status': 1, 'leaseUntil': 1}"),
        @CompoundIndex(name = "ws_contact_active", def = "{'workspaceId': 1, 'contactId': 1}", unique = true,
                partialFilter = "{'status': {'$in': ['RUNNING', 'WAITING']}}"),
        @CompoundIndex(name = "event_rule_live", def = "{'startedBy.eventId': 1, 'startedBy.ruleId': 1}", unique = true,
                partialFilter = "{'test': false, 'startedBy.eventId': {'$exists': true}, 'startedBy.ruleId': {'$exists': true}}")
})
@Getter
@Setter
public class ChatbotRun {

    @Id
    private String id;
    private String customerId;
    private String workspaceId;
    private String chatbotId;
    private String chatbotName;
    private int versionNumber;
    private String contactId;
    private String chatId;
    private String phoneNumberId;
    private ChatbotRunStatus status;
    private String currentNodeId;
    private String currentNodeName;
    private Map<String, Object> variables;
    private List<ChatbotStep> steps;
    private boolean test;
    private StartedBy startedBy;
    private Waiting waiting;
    private String lastAnswerKey;
    private int invalidReplies;
    private int sendAttempts;
    private int jumps;
    private Instant leaseUntil;
    private Instant remindAt;
    private Instant endAt;
    private boolean reminderSent;
    private String endReason;
    private String stoppedBy;
    private Instant retryAt;
    private Instant createdAt;
    private Instant finishedAt;
    /** Every save is conditional on it: a runner that lost the run to a Stop cannot write it back. */
    @Version
    private Long version;
}
