package me.nageshk.omnichannel.db.chatbot;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** The platform's or one brand's chatbot settings layer; the platform document has no {@code workspaceId}. */
@Document("chatbotSettings")
@CompoundIndex(name = "scope_workspace_unique", def = "{'scope': 1, 'workspaceId': 1}", unique = true)
@Getter
@Setter
public class ChatbotSettingsDocument {

    @Id
    private String id;
    private ChatbotSettingsScope scope;
    private String workspaceId;
    private ChatbotSettingsLayer settings;
    private Instant lastUpdateTime;
    private String updatedBy;
    @Version
    private Long version;
}
