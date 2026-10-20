package me.nageshk.omnichannel.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/** An open stream keeps checking the viewer's access: a user who loses it is dropped, not kept informed. */
class InboxStreamIT extends InboxApiTestBase {

    @Autowired InboxStreamBroker broker;

    private MvcResult open(String token) throws Exception {
        MvcResult result = send(get(inbox() + "/phone-numbers/" + number.getId() + "/stream"), token, null)
                .andExpect(request().asyncStarted()).andReturn();
        assertThat(broker.subscribers(number.getId())).isEqualTo(1);
        return result;
    }

    private static String sent(MvcResult stream) throws Exception {
        return stream.getResponse().getContentAsString();
    }

    private ChatView view(WaWebChat chat) {
        return new ChatView(chat.getId(), number.getId(), chat.getRemoteId(), null, ChatStatus.OPEN, null, null, 0,
                null, null, null);
    }

    @Test
    void streamOfAStillAuthorizedUserKeepsReceivingPingsAndPushes() throws Exception {
        MvcResult stream = open(agentToken);
        WaWebChat mine = chat("Asha", "919800000001", 1, agent, null);

        broker.ping();
        broker.push(number.getId(), mine, view(mine));

        assertThat(broker.subscribers(number.getId())).isEqualTo(1);
        assertThat(sent(stream)).contains("event:chat").contains("\"visible\":true");
    }

    @Test
    void disabledUserIsDroppedOnTheNextPing() throws Exception {
        MvcResult stream = open(agentToken);
        agent.setActive(false);
        users.save(agent);

        broker.ping();

        assertThat(broker.subscribers(number.getId())).isZero();
        assertThat(sent(stream)).doesNotContain("ping");
    }

    @Test
    void loggedOutUserIsDroppedOnTheNextPush() throws Exception {
        MvcResult stream = open(agentToken);
        WaWebChat mine = chat("Asha", "919800000001", 1, agent, null);
        agent.setTokenVersion(agent.getTokenVersion() + 1);
        users.save(agent);

        broker.push(number.getId(), mine, view(mine));

        assertThat(broker.subscribers(number.getId())).isZero();
        assertThat(sent(stream)).doesNotContain("event:chat").doesNotContain("Asha");
    }

    @Test
    void userRemovedFromTheBrandIsDropped() throws Exception {
        MvcResult stream = open(agentToken);
        agent.setWorkspaceRoles(java.util.List.of());
        users.save(agent);

        broker.ping();

        assertThat(broker.subscribers(number.getId())).isZero();
        assertThat(sent(stream)).doesNotContain("ping");
    }
}
