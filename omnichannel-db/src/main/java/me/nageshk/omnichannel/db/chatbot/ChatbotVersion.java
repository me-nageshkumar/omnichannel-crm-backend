package me.nageshk.omnichannel.db.chatbot;

import java.time.Instant;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** A saved flow; the newest 50 are kept, never one an active run points to (spec §4.1). */
@Document("chatbotVersions")
@CompoundIndex(name = "chatbot_version", def = "{'chatbotId': 1, 'versionNumber': 1}", unique = true)
@Getter
@Setter
public class ChatbotVersion {

    @Id
    private String id;
    private String workspaceId;
    private String chatbotId;
    private int versionNumber;
    private Map<String, Object> flow;
    private Instant savedAt;
    private String savedBy;
}
