package me.nageshk.omnichannel.app.ai;

import java.util.ArrayList;
import java.util.List;
import me.nageshk.omnichannel.ai.call.AiCall;
import me.nageshk.omnichannel.ai.connection.AiConnection;
import me.nageshk.omnichannel.ai.conversation.AiConversation;
import me.nageshk.omnichannel.app.chatbot.ChatbotTestBase;
import me.nageshk.omnichannel.app.support.OpenAiStub;
import me.nageshk.omnichannel.app.support.ScriptedAiProvider;
import me.nageshk.omnichannel.db.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/** The chatbot fixture (flow builders, chatbot collections) plus the AI collections, the real OpenAI adapter pointed at a local stub, and the scripted provider. */
@Import(ScriptedAiProvider.class)
public abstract class AiTestBase extends ChatbotTestBase {

    protected static final OpenAiStub OPENAI = new OpenAiStub();

    @DynamicPropertySource
    static void openAi(DynamicPropertyRegistry registry) {
        registry.add("omnichannel.ai.openai.base-url", OPENAI::baseUrl);
    }

    @Autowired protected ScriptedAiProvider scripted;

    @Override
    protected List<Class<?>> collections() {
        List<Class<?>> c = new ArrayList<>(super.collections());
        c.add(AiConnection.class);
        c.add(AiCall.class);
        c.add(AiConversation.class);
        return c;
    }

    @BeforeEach
    void resetAiDoubles() {
        OPENAI.reset();
        scripted.reset();
        mongo.remove(new org.springframework.data.mongodb.core.query.Query(), "aiProfiles");
    }

    /** {@code path} is relative to the brand's {@code /api/workspaces/{id}/ai}. */
    protected ResponseEntity<JsonNode> ai(HttpMethod m, String path, User who, Object body) {
        return rest.exchange(url("/api/workspaces/" + brand.getId() + "/ai" + path), m, auth(who, body),
                JsonNode.class);
    }
}
