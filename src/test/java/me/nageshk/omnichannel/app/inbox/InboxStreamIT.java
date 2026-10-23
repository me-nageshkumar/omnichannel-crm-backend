package me.nageshk.omnichannel.app.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import me.nageshk.omnichannel.db.inbox.ChatStatus;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.phone.PhoneNumber;
import me.nageshk.omnichannel.db.tenant.Team;
import me.nageshk.omnichannel.service.inbox.ChatView;
import me.nageshk.omnichannel.service.inbox.InboxStreamBroker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/** An open stream keeps checking the viewer's access: a user who loses it is dropped, not kept informed. */
class InboxStreamIT extends InboxApiTestBase {

    @Autowired InboxStreamBroker broker;

    private MvcResult open(String token) throws Exception {
        return open(token, null);
    }

    private MvcResult open(String token, String phoneNumberIds) throws Exception {
        var req = get(inbox() + "/stream");
        if (phoneNumberIds != null) req = req.param("phoneNumberIds", phoneNumberIds);
        MvcResult result = send(req, token, null).andExpect(request().asyncStarted()).andReturn();
        assertThat(broker.subscribers(brand.getId())).isEqualTo(1);
        return result;
    }

    private PhoneNumber ownedNumber(Team owner, String digits) {
        PhoneNumber n = testDevices.number(brand, digits + (System.nanoTime() % 1000000));
        n.setTeamId(owner == null ? null : owner.getId());
        return phoneNumbers.save(n);
    }

    private static String sent(MvcResult stream) throws Exception {
        return stream.getResponse().getContentAsString();
    }

    private ChatView view(WaWebChat chat) {
        return new ChatView(chat.getId(), chat.getPhoneNumberId(), chat.getRemoteId(), null, ChatStatus.OPEN, null, null,
                0, null, null, null, null);
    }

    @Test
    void streamOfAStillAuthorizedUserKeepsReceivingPingsAndPushes() throws Exception {
        MvcResult stream = open(agentToken);
        WaWebChat mine = chat("Asha", "919800000001", 1, agent, null);

        broker.ping();
        broker.push(brand.getId(), mine, view(mine));

        assertThat(broker.subscribers(brand.getId())).isEqualTo(1);
        assertThat(sent(stream)).contains("event:chat").contains("\"visible\":true");
    }

    @Test
    void disabledUserIsDroppedOnTheNextPing() throws Exception {
        MvcResult stream = open(agentToken);
        agent.setActive(false);
        users.save(agent);

        broker.ping();

        assertThat(broker.subscribers(brand.getId())).isZero();
        assertThat(sent(stream)).doesNotContain("ping");
    }

    @Test
    void loggedOutUserIsDroppedOnTheNextPush() throws Exception {
        MvcResult stream = open(agentToken);
        WaWebChat mine = chat("Asha", "919800000001", 1, agent, null);
        agent.setTokenVersion(agent.getTokenVersion() + 1);
        users.save(agent);

        broker.push(brand.getId(), mine, view(mine));

        assertThat(broker.subscribers(brand.getId())).isZero();
        assertThat(sent(stream)).doesNotContain("event:chat").doesNotContain("Asha");
    }

    /**
     * When a stream ends, the container re-dispatches the request (ASYNC) through the security chain. The
     * bearer token may be dead by then (that is often why the stream ended), so that dispatch must be let
     * through rather than refused and logged as "Access Denied" on a response that is already closed.
     */
    @Test
    void endingAStreamWhoseTokenDiedDoesNotTripAuthorizationOnTheAsyncDispatch() throws Exception {
        MvcResult stream = open(agentToken);
        agent.setTokenVersion(agent.getTokenVersion() + 1);
        users.save(agent);
        broker.ping();
        assertThat(broker.subscribers(brand.getId())).isZero();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(stream))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }

    @Test
    void userRemovedFromTheBrandIsDropped() throws Exception {
        MvcResult stream = open(agentToken);
        agent.setWorkspaceRoles(java.util.List.of());
        users.save(agent);

        broker.ping();

        assertThat(broker.subscribers(brand.getId())).isZero();
        assertThat(sent(stream)).doesNotContain("ping");
    }

    @Test
    void streamSpansTwoNumbers() throws Exception {
        PhoneNumber b = ownedNumber(support, "9188");
        MvcResult stream = open(agentToken);
        WaWebChat onA = chat("Asha", "919800000001", 1, agent, null);
        WaWebChat onB = chat(b, brand, contact(brand, "Bela", "919800000002"), 2);
        onB.setAssignedUserId(agent.getId());
        onB = chats.save(onB);

        broker.push(brand.getId(), onA, view(onA));
        broker.push(brand.getId(), onB, view(onB));

        assertThat(sent(stream)).contains("\"id\":\"" + onA.getId() + "\"")
                .contains("\"id\":\"" + onB.getId() + "\"");
    }

    @Test
    void filteredStreamDropsOtherNumbers() throws Exception {
        PhoneNumber b = ownedNumber(support, "9188");
        MvcResult stream = open(agentToken, b.getId());
        WaWebChat onA = chat("Asha", "919800000001", 1, agent, null);

        broker.push(brand.getId(), onA, view(onA));

        assertThat(sent(stream)).doesNotContain(onA.getId());
    }

    @Test
    void streamDeliversGuestChat() throws Exception {
        Team sales = team("Sales", agent2);
        PhoneNumber c = ownedNumber(sales, "9177");
        WaWebChat handedToMe = chat(c, brand, contact(brand, "Chitra", "919800000003"), 3);
        handedToMe.setAssignedUserId(agent.getId());
        handedToMe = chats.save(handedToMe);
        MvcResult stream = open(agentToken);

        broker.push(brand.getId(), handedToMe, view(handedToMe));

        assertThat(sent(stream)).contains("\"id\":\"" + handedToMe.getId() + "\"").contains("\"visible\":true");
    }

    @Test
    void streamRequestingForeignNumberIs404() throws Exception {
        Team sales = team("Sales", agent2);
        PhoneNumber c = ownedNumber(sales, "9177");
        send(get(inbox() + "/stream").param("phoneNumberIds", c.getId()), agentToken, null)
                .andExpect(status().isNotFound());
        send(get(inbox() + "/stream").param("phoneNumberIds", otherNumber.getId()), managerToken, null)
                .andExpect(status().isNotFound());
        assertThat(broker.subscribers(brand.getId())).isZero();
    }
}
