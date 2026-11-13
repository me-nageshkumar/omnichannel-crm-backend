package me.nageshk.omnichannel.app.chatbot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import me.nageshk.omnichannel.app.support.TestUsers;
import me.nageshk.omnichannel.db.chatbot.Chatbot;
import me.nageshk.omnichannel.db.chatbot.ChatbotRepository;
import me.nageshk.omnichannel.db.chatbot.ChatbotRun;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunRepository;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunStatus;
import me.nageshk.omnichannel.db.chatbot.ChatbotSettings;
import me.nageshk.omnichannel.db.chatbot.ChatbotStatus;
import me.nageshk.omnichannel.db.chatbot.StartedBy;
import me.nageshk.omnichannel.db.chatbot.TaskKind;
import me.nageshk.omnichannel.db.chatbot.Waiting;
import me.nageshk.omnichannel.security.access.Authority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;

/** Spec §4.1, §5.1, §9: the chatbot documents' uniqueness rules and the new authorities. */
@SpringBootTest
class ChatbotModelIT {

    @Autowired TestUsers testUsers;
    @Autowired ChatbotRepository chatbots;
    @Autowired ChatbotRunRepository runs;

    @BeforeEach
    @AfterEach
    void clean() {
        testUsers.deleteAll();
    }

    private Chatbot chatbot(String ws, String name) {
        Chatbot c = new Chatbot();
        c.setCustomerId("cust");
        c.setWorkspaceId(ws);
        c.setName(name);
        c.setStatus(ChatbotStatus.DRAFT);
        c.setFlow(Map.of("nodes", java.util.List.of(), "edges", java.util.List.of()));
        c.setSettings(ChatbotSettings.defaults());
        c.setCreationTime(Instant.now());
        return c;
    }

    private ChatbotRun run(String contactId, ChatbotRunStatus status, StartedBy by, boolean test) {
        ChatbotRun r = new ChatbotRun();
        r.setCustomerId("cust");
        r.setWorkspaceId("w1");
        r.setChatbotId("c1");
        r.setChatbotName("Lead qualifier");
        r.setContactId(contactId);
        r.setStatus(status);
        r.setStartedBy(by);
        r.setTest(test);
        r.setCreatedAt(Instant.now());
        return r;
    }

    @Test
    void uniqueNamePerBrand() {
        chatbots.insert(chatbot("w1", "Lead qualifier"));
        assertThatThrownBy(() -> chatbots.insert(chatbot("w1", "Lead qualifier"))).isInstanceOf(DuplicateKeyException.class);
        assertThat(chatbots.insert(chatbot("w2", "Lead qualifier")).getId()).isNotNull();
    }

    @Test
    void oneLiveRunPerEventAndRule() {
        StartedBy by = new StartedBy("r1", "Welcome", "e1", null);
        runs.insert(run("k1", ChatbotRunStatus.COMPLETED, by, false));
        assertThatThrownBy(() -> runs.insert(run("k2", ChatbotRunStatus.COMPLETED, by, false)))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(runs.insert(run("k3", ChatbotRunStatus.COMPLETED, by, true)).getId()).isNotNull();
    }

    @Test
    void oneActiveRunPerContact() {
        runs.insert(run("k1", ChatbotRunStatus.WAITING, null, false));
        assertThatThrownBy(() -> runs.insert(run("k1", ChatbotRunStatus.RUNNING, null, false)))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(runs.insert(run("k1", ChatbotRunStatus.COMPLETED, null, false)).getId()).isNotNull();
        assertThat(runs.insert(run("k2", ChatbotRunStatus.WAITING, null, false)).getId()).isNotNull();
    }

    @Test
    void waitingPairIsIndexed() {
        ChatbotRun r = run("k1", ChatbotRunStatus.WAITING, null, false);
        r.setWaiting(new Waiting(TaskKind.WHATSAPP_REPLY, "chat-1", Instant.now(), null, "Hi"));
        runs.insert(r);
        assertThat(runs.findFirstByWaitingKindAndWaitingKeyAndStatus(TaskKind.WHATSAPP_REPLY, "chat-1",
                ChatbotRunStatus.WAITING)).isPresent();
        assertThat(runs.findFirstByWaitingKindAndWaitingKeyAndStatus(TaskKind.FORM, "chat-1",
                ChatbotRunStatus.WAITING)).isEmpty();
        assertThat(ChatbotRunStatus.WAITING.active()).isTrue();
        assertThat(ChatbotRunStatus.COMPLETED.active()).isFalse();
    }

    @Test
    void authoritiesExist() {
        assertThat(Authority.valueOf("CHATBOT_MANAGE").code()).isEqualTo("chatbot:manage");
        assertThat(Authority.valueOf("CHATBOT_MANAGE").defaultRoles()).contains("WORKSPACE_MANAGER", "CUSTOMER_ADMIN");
        assertThat(Authority.valueOf("CHATBOT_VIEW").code()).isEqualTo("chatbot:view");
    }
}
