package me.nageshk.omnichannel.db.chatbot;

import java.time.Instant;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A chatbot: its flow as the canvas drew it (kept verbatim, positions and viewport included; the server reads only
 * what {@code FlowParser} needs), its settings and status. {@code versionNumber} counts saved versions.
 */
@Document("chatbots")
@CompoundIndex(name = "ws_name", def = "{'workspaceId': 1, 'name': 1}", unique = true)
@Getter
@Setter
public class Chatbot {

    @Id
    private String id;
    private String customerId;
    private String workspaceId;
    private String name;
    private String description;
    private ChatbotStatus status;
    private Map<String, Object> flow;
    private ChatbotSettings settings;
    private int versionNumber;
    @Version
    private Long version;
    private String createdBy;
    private Instant creationTime;
    private Instant lastUpdateTime;
    private Instant publishedAt;
    private String publishedBy;
}
