package me.nageshk.omnichannel.app.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.util.UUID;
import me.nageshk.omnichannel.app.chatbot.ChatbotTestBase;
import me.nageshk.omnichannel.db.inbox.ChatStatus;
import me.nageshk.omnichannel.db.inbox.RoutedReason;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.service.inbox.BotChats;
import me.nageshk.omnichannel.service.inbox.ChatRoutingService;
import me.nageshk.omnichannel.service.inbox.RoutingGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** A customer message saved between a status move and the routing save must not leave the chat with nobody. */
class BotRoutingRetryIT extends ChatbotTestBase {

    @MockitoSpyBean ChatRoutingService routing;
    @Autowired BotChats botChats;
    @Autowired RoutingGate gate;

    @AfterEach
    void real() {
        reset(routing);
    }

    private WaWebChat chat(ChatStatus status) {
        WaWebChat c = new WaWebChat();
        c.setCustomerId(acme.getId());
        c.setWorkspaceId(brand.getId());
        c.setPhoneNumberId(n1.getId());
        c.setRemoteId(UUID.randomUUID() + "@c.us");
        c.setStatus(status);
        return chats.save(c);
    }

    private WaWebChat fresh(WaWebChat c) {
        return chats.findById(c.getId()).orElseThrow();
    }

    @Test
    void toPeopleRoutesAgainAfterALostRace() {
        WaWebChat c = chat(ChatStatus.BOT);
        doThrow(new OptimisticLockingFailureException("a message landed")).doCallRealMethod().when(routing)
                .route(org.mockito.ArgumentMatchers.any(WaWebChat.class), org.mockito.ArgumentMatchers.any(ChatRoutingService.RoutingOptions.class));

        botChats.toPeople(c.getId(), null, RoutedReason.CHATBOT_ENDED, "Welcome");

        assertThat(fresh(c).getStatus()).isEqualTo(ChatStatus.OPEN);
        assertThat(fresh(c).getAssignedTeamId()).isEqualTo(support.getId());
    }

    @Test
    void settleThatGivesUpLeavesTheChatForTheSweep() {
        WaWebChat c = chat(ChatStatus.OPEN);
        c.setRoutingPendingSince(clock.instant());
        chats.save(c);
        doThrow(new OptimisticLockingFailureException("busy")).when(routing).route(org.mockito.ArgumentMatchers.any(WaWebChat.class));

        assertThat(gate.settle(c.getId())).isFalse();

        assertThat(fresh(c).getRoutingPendingSince()).isNotNull();
        doCallRealMethod().when(routing).route(org.mockito.ArgumentMatchers.any(WaWebChat.class));
        assertThat(gate.settle(c.getId())).isTrue();
        assertThat(fresh(c).getAssignedTeamId()).isEqualTo(support.getId());
    }
}
