package me.nageshk.omnichannel.db.chatbot;

import java.time.Instant;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** A form page a run sent and waits for (spec §8.4); the token is the capability, the run's FORM task's key. */
@Document("chatbotForms")
@Getter
@Setter
public class ChatbotForm {

    @Id
    private String id;
    private String workspaceId;
    private String runId;
    private String nodeId;
    @Indexed(unique = true)
    private String token;
    private Map<String, Object> definition;
    private Map<String, Object> answers;
    private Instant submittedAt;
    private Instant expiresAt;
    private Instant createdAt;
}
