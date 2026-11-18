package me.nageshk.omnichannel.app.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import me.nageshk.omnichannel.app.chatbot.ChatbotTestBase;
import me.nageshk.omnichannel.db.automation.AutomationAction;
import me.nageshk.omnichannel.db.automation.AutomationActionType;
import me.nageshk.omnichannel.db.automation.AutomationEventKind;
import me.nageshk.omnichannel.db.automation.RuleStatus;
import me.nageshk.omnichannel.db.chatbot.ChatbotRun;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunRepository;
import me.nageshk.omnichannel.db.chatbot.ChatbotRunStatus;
import me.nageshk.omnichannel.db.chatbot.IfRunning;
import me.nageshk.omnichannel.db.inbox.ActionType;
import me.nageshk.omnichannel.db.inbox.ChatStatus;
import me.nageshk.omnichannel.db.inbox.RoutedReason;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.inbox.WaWebChatAction;
import me.nageshk.omnichannel.service.chatbot.run.ChatbotTicker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

/** Spec 2026-11-18 §5.5: the ticker repairs chats left waiting for rules and chatbot chats left without a run. */
class BotSweepIT extends ChatbotTestBase {

    static final String VEENU = "919799750743@c.us";

    @Autowired ChatbotTicker ticker;
    @Autowired ChatbotRunRepository runRepo;

    private void tick() throws Exception {
        ticker.tick().get(5, TimeUnit.SECONDS);
    }

    private WaWebChat fresh(WaWebChat c) {
        return chats.findById(c.getId()).orElseThrow();
    }

    /** A BOT chat whose pointer names a run of {@code status} that finished {@code ago}. */
    private WaWebChat botWithRun(ChatbotRunStatus status, Duration ago) {
        WaWebChat c = new WaWebChat();
        c.setCustomerId(acme.getId());
        c.setWorkspaceId(brand.getId());
        c.setPhoneNumberId(n1.getId());
        c.setRemoteId(UUID.randomUUID() + "@c.us");
        c.setStatus(ChatStatus.BOT);
        c.setLastMessageAt(clock.instant().minus(Duration.ofHours(1)));
        c = chats.save(c);
        ChatbotRun r = new ChatbotRun();
        r.setCustomerId(acme.getId());
        r.setWorkspaceId(brand.getId());
        r.setChatbotId("cb1");
        r.setChatbotName("Welcome");
        r.setContactId("contact-" + UUID.randomUUID());
        r.setChatId(c.getId());
        r.setStatus(status);
        r.setCreatedAt(clock.instant().minus(Duration.ofHours(1)));
        if (!status.active()) r.setFinishedAt(clock.instant().minus(ago));
        r = runRepo.insert(r);
        c.setChatbot(new WaWebChat.ActiveChatbot(r.getId(), "cb1", "Welcome", "Ask"));
        return chats.save(c);
    }

    private List<WaWebChatAction> endedRoutes(WaWebChat c) {
        return mongo.findAll(WaWebChatAction.class).stream().filter(a -> a.getChatId().equals(c.getId())
                && a.getType() == ActionType.ROUTED && a.getReason() == RoutedReason.CHATBOT_ENDED).toList();
    }

    @Test
    void aChatLeftWaitingIsRoutedAfterTheWait() throws Exception {
        String id = createChatbot("Welcome", flow(List.of(node("q", "ask_question", "AskName",
                Map.of("text", "Name?", "answerType", "TEXT"))), List.of())).get("id").asString();
        assertThat(bots(HttpMethod.POST, "/" + id + "/publish", manager, null).getStatusCode().is2xxSuccessful()).isTrue();
        rule("Welcome", AutomationEventKind.MESSAGE_RECEIVED, RuleStatus.PUBLISHED, 1, null,
                new AutomationAction(AutomationActionType.START_CHATBOT, null, null, null, null, null, null, null, null,
                        null, id, IfRunning.SKIP));
        // the rule worker never runs
        WaWebChat c = fresh(incoming(n1, VEENU, "chat", "hello", "Veenu"));
        assertThat(c.getRoutingPendingSince()).isNotNull();

        clock.advance(Duration.ofSeconds(29));
        tick();
        assertThat(fresh(c).getRoutingPendingSince()).isNotNull();
        assertThat(fresh(c).getAssignedTeamId()).isNull();

        clock.advance(Duration.ofSeconds(2));
        tick();
        WaWebChat after = fresh(c);
        assertThat(after.getRoutingPendingSince()).isNull();
        assertThat(after.getAssignedTeamId()).isEqualTo(support.getId());
    }

    @Test
    void aBotChatWithoutARunGoesToTheTeam() throws Exception {
        WaWebChat c = botWithRun(ChatbotRunStatus.COMPLETED, Duration.ofSeconds(31));

        tick();

        WaWebChat after = fresh(c);
        assertThat(after.getStatus()).isEqualTo(ChatStatus.OPEN);
        assertThat(after.getAssignedTeamId()).isEqualTo(support.getId());
        assertThat(endedRoutes(c)).hasSize(1);
        assertThat(endedRoutes(c).get(0).getByName()).isEqualTo("Chatbot: Welcome");
    }

    @Test
    void aBotChatWhoseRunJustEndedIsLeftAlone() throws Exception {
        WaWebChat c = botWithRun(ChatbotRunStatus.JUMPED, Duration.ofSeconds(5));

        tick();

        assertThat(fresh(c).getStatus()).isEqualTo(ChatStatus.BOT);
        assertThat(endedRoutes(c)).isEmpty();
    }

    @Test
    void aBotChatWithAnActiveRunIsLeftAlone() throws Exception {
        WaWebChat c = botWithRun(ChatbotRunStatus.WAITING, Duration.ZERO);
        clock.advance(Duration.ofMinutes(5));

        tick();

        assertThat(fresh(c).getStatus()).isEqualTo(ChatStatus.BOT);
    }
}
