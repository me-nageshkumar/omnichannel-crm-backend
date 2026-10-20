package me.nageshk.omnichannel.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.access.BuiltInRoles;
import me.nageshk.omnichannel.enablement.Disabled;
import me.nageshk.omnichannel.enablement.EnableLevel;
import me.nageshk.omnichannel.tenant.Team;
import me.nageshk.omnichannel.user.BrandDisable;
import me.nageshk.omnichannel.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

class InboxApiIT extends InboxApiTestBase {

    @Test
    void agentListsOnlyOwnChats() throws Exception {
        chat("Alice", "911", 1, agent, null);
        chat("Bob", "912", 2, agent2, null);
        chat("Carol", "913", 3, null, null);

        JsonNode page = json(send(get(chatsUrl()), agentToken, null).andExpect(status().isOk()));

        assertThat(names(page)).containsExactly("Alice");
        assertThat(page.get("hasMore").asBoolean()).isFalse();
        JsonNode item = page.get("items").get(0);
        assertThat(item.get("phoneNumberId").asString()).isEqualTo(number.getId());
        assertThat(item.get("assignedUser").get("id").asString()).isEqualTo(agent.getId());
        assertThat(item.get("assignedUser").get("name").asString()).isEqualTo("Test agent");
        assertThat(item.get("contact").get("phone").asString()).isEqualTo("911");
        assertThat(item.get("unreadCount").asInt()).isEqualTo(2);
        assertThat(item.get("lastMessage").get("text").asString()).isEqualTo("hello");
    }

    @Test
    void agentCannotRequestTeamScope() throws Exception {
        send(get(chatsUrl()).param("scope", "TEAM"), agentToken, null).andExpect(status().isForbidden());
        send(get(chatsUrl()).param("scope", "UNASSIGNED"), agentToken, null).andExpect(status().isForbidden());
        send(get(chatsUrl()).param("scope", "ALL"), leadToken, null).andExpect(status().isForbidden());
        send(get(chatsUrl()).param("scope", "MINE"), agentToken, null).andExpect(status().isOk());
    }

    @Test
    void unknownScopeIsBadRequest() throws Exception {
        send(get(chatsUrl()).param("scope", "EVERYTHING"), managerToken, null).andExpect(status().isBadRequest());
    }

    @Test
    void leadSeesTeamQueueAndUnassignedAndOwn() throws Exception {
        chat("OnTeam", "911", 1, null, support);
        chat("Queue", "912", 2, null, null);
        chat("Own", "913", 3, lead, null);
        chat("Theirs", "914", 4, agent2, null);
        Team sales = team("Sales", agent2);
        chat("OtherTeam", "915", 5, null, sales);

        JsonNode page = json(send(get(chatsUrl()), leadToken, null).andExpect(status().isOk()));

        // default scope is TEAM: team-routed and unassigned; the lead's own chat needs the MINE list
        assertThat(names(page)).containsExactly("Queue", "OnTeam");
        assertThat(names(json(send(get(chatsUrl()).param("scope", "MINE"), leadToken, null))))
                .containsExactly("Own");
        assertThat(names(json(send(get(chatsUrl()).param("scope", "UNASSIGNED"), leadToken, null))))
                .containsExactly("Queue");
    }

    @Test
    void managerSeesAll() throws Exception {
        chat("A", "911", 1, agent, null);
        chat("B", "912", 2, agent2, null);
        chat("C", "913", 3, null, support);
        chat("D", "914", 4, null, null);

        JsonNode page = json(send(get(chatsUrl()), managerToken, null).andExpect(status().isOk()));

        assertThat(names(page)).containsExactly("D", "C", "B", "A");
    }

    @Test
    void chatOutsideScopeIs404() throws Exception {
        WaWebChat theirs = chat("Bob", "912", 1, agent2, null);
        WaWebChat mine = chat("Alice", "911", 2, agent, null);

        send(get(inbox() + "/chats/" + theirs.getId()), agentToken, null).andExpect(status().isNotFound());
        send(get(inbox() + "/chats/" + theirs.getId() + "/thread"), agentToken, null).andExpect(status().isNotFound());
        send(post(inbox() + "/chats/" + theirs.getId() + "/read"), agentToken, null).andExpect(status().isNotFound());
        send(post(inbox() + "/chats/" + theirs.getId() + "/close"), agentToken, null).andExpect(status().isNotFound());
        send(get(inbox() + "/chats/" + mine.getId()), agentToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(mine.getId()))
                .andExpect(jsonPath("$.contact.name").value("Alice"));
        send(get(inbox() + "/chats/" + theirs.getId()), managerToken, null).andExpect(status().isOk());
        send(get(inbox() + "/chats/missing"), managerToken, null).andExpect(status().isNotFound());
    }

    @Test
    void chatOfAnotherWorkspaceIs404() throws Exception {
        WaWebChat foreign = chat(otherNumber, other, contact(other, "Zed", "999"), 1);

        send(get(inbox() + "/chats/" + foreign.getId()), managerToken, null).andExpect(status().isNotFound());
    }

    @Test
    void numberOfAnotherWorkspaceIs404() throws Exception {
        String foreign = inbox() + "/phone-numbers/" + otherNumber.getId();

        send(get(foreign + "/chats"), managerToken, null).andExpect(status().isNotFound());
        send(get(foreign + "/stream"), managerToken, null).andExpect(status().isNotFound());
        send(get(foreign + "/settings"), managerToken, null).andExpect(status().isNotFound());
        send(put(foreign + "/settings"), managerToken, Map.of("mode", "NONE")).andExpect(status().isNotFound());
    }

    @Test
    void searchByNamePrefix() throws Exception {
        chat("Alice Smith", "911", 1);
        chat("Bob alison", "912", 2);
        chat("Malice", "913", 3);
        chat("Dan", "9155", 4);

        assertThat(names(json(send(get(chatsUrl()).param("q", "ali"), managerToken, null))))
                .containsExactly("Alice Smith");
        assertThat(names(json(send(get(chatsUrl()).param("q", "AL"), managerToken, null))))
                .containsExactly("Alice Smith");
        assertThat(names(json(send(get(chatsUrl()).param("q", "9155"), managerToken, null))))
                .containsExactly("Dan");
        assertThat(names(json(send(get(chatsUrl()).param("q", "zz"), managerToken, null)))).isEmpty();
        // regex metacharacters are literals
        assertThat(names(json(send(get(chatsUrl()).param("q", ".*"), managerToken, null)))).isEmpty();
    }

    @Test
    void closedFilterAndUnreadFilter() throws Exception {
        WaWebChat closed = chat("Closed", "911", 1);
        closed.setStatus(ChatStatus.CLOSED);
        chats.save(closed);
        WaWebChat read = chat("Read", "912", 2);
        read.setUnreadCount(0);
        chats.save(read);
        chat("Open", "913", 3);

        assertThat(names(json(send(get(chatsUrl()), managerToken, null)))).containsExactly("Open", "Read");
        assertThat(names(json(send(get(chatsUrl()).param("status", "CLOSED"), managerToken, null))))
                .containsExactly("Closed");
        assertThat(names(json(send(get(chatsUrl()).param("status", "ALL"), managerToken, null))))
                .containsExactly("Open", "Read", "Closed");
        assertThat(names(json(send(get(chatsUrl()).param("unread", "true"), managerToken, null))))
                .containsExactly("Open");
    }

    @Test
    void cursorPaging() throws Exception {
        chat("One", "911", 1);
        chat("Two", "912", 2);
        chat("Three", "913", 3);

        JsonNode first = json(send(get(chatsUrl()).param("limit", "2"), managerToken, null));
        assertThat(names(first)).containsExactly("Three", "Two");
        assertThat(first.get("hasMore").asBoolean()).isTrue();

        String cursor = first.get("items").get(1).get("lastMessageAt").asString();
        JsonNode second = json(send(get(chatsUrl()).param("limit", "2").param("before", cursor), managerToken, null));
        assertThat(names(second)).containsExactly("One");
        assertThat(second.get("hasMore").asBoolean()).isFalse();
    }

    @Test
    void badLimitIsRejected() throws Exception {
        send(get(chatsUrl()).param("limit", "0"), managerToken, null).andExpect(status().isBadRequest());
        send(get(chatsUrl()).param("limit", "101"), managerToken, null).andExpect(status().isBadRequest());
    }

    @Test
    void threadIsOldestFirstWithActions() throws Exception {
        WaWebChat c = chat("Alice", "911", 1, agent, null);
        message(c, 10, "m1");
        action(c, 20, ActionType.ASSIGNED);
        message(c, 30, "m2");
        message(c, 40, "m3");
        action(c, 50, ActionType.CLOSED);

        JsonNode all = json(send(get(inbox() + "/chats/" + c.getId() + "/thread"), agentToken, null)
                .andExpect(status().isOk()));

        assertThat(all.get("hasMore").asBoolean()).isFalse();
        List<String> kinds = new java.util.ArrayList<>();
        all.get("items").forEach(i -> kinds.add(i.get("kind").asString() + ":"
                + (!i.get("message").isNull() ? i.get("message").get("body").asString() : i.get("action").get("type").asString())));
        assertThat(kinds).containsExactly("message:m1", "action:ASSIGNED", "message:m2", "message:m3",
                "action:CLOSED");
        assertThat(all.get("items").get(0).get("at").asString()).isEqualTo(T0.plusSeconds(10).toString());
        assertThat(all.get("items").get(1).get("action").get("by").isNull()).isTrue();

        JsonNode page = json(send(get(inbox() + "/chats/" + c.getId() + "/thread").param("limit", "2"), agentToken,
                null));
        assertThat(page.get("hasMore").asBoolean()).isTrue();
        assertThat(page.get("items")).hasSize(2);
        assertThat(page.get("items").get(0).get("message").get("body").asString()).isEqualTo("m3");
        assertThat(page.get("items").get(1).get("action").get("type").asString()).isEqualTo("CLOSED");

        String before = page.get("items").get(0).get("at").asString();
        JsonNode older = json(send(get(inbox() + "/chats/" + c.getId() + "/thread").param("limit", "10")
                .param("before", before), agentToken, null));
        assertThat(older.get("hasMore").asBoolean()).isFalse();
        assertThat(older.get("items")).hasSize(3);
        assertThat(older.get("items").get(0).get("message").get("body").asString()).isEqualTo("m1");
        assertThat(older.get("items").get(2).get("message").get("body").asString()).isEqualTo("m2");
    }

    @Test
    void threadOnlyHoldsItsOwnChat() throws Exception {
        WaWebChat c = chat("Alice", "911", 1, agent, null);
        WaWebChat d = chat("Dan", "912", 2, agent, null);
        message(c, 10, "mine");
        message(d, 11, "theirs");

        JsonNode thread = json(send(get(inbox() + "/chats/" + c.getId() + "/thread"), agentToken, null));

        assertThat(thread.get("items")).hasSize(1);
        assertThat(thread.get("items").get(0).get("message").get("body").asString()).isEqualTo("mine");
    }

    @Test
    void readZeroesUnread() throws Exception {
        WaWebChat c = chat("Alice", "911", 1, agent, null);

        send(post(inbox() + "/chats/" + c.getId() + "/read"), agentToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(0));

        assertThat(chats.findById(c.getId()).orElseThrow().getUnreadCount()).isZero();
    }

    @Test
    void assignNeedsAuthority() throws Exception {
        WaWebChat c = chat("Alice", "911", 1, agent, null);
        String url = inbox() + "/chats/" + c.getId() + "/assign";

        send(post(url), agentToken, Map.of("userId", agent2.getId())).andExpect(status().isForbidden());

        // the lead can see it once it is in the team queue
        c.setAssignedUserId(null);
        c.setAssignedTeamId(support.getId());
        chats.save(c);
        send(post(url), leadToken, Map.of("userId", lead.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedUser.name").value("Test lead"))
                .andExpect(jsonPath("$.assignedUser.id").value(lead.getId()));
        assertThat(chats.findById(c.getId()).orElseThrow().getAssignedUserId()).isEqualTo(lead.getId());

        send(post(url), leadToken, Map.of()).andExpect(status().isBadRequest());
    }

    @Test
    void unassignClearsAssignment() throws Exception {
        WaWebChat c = chat("Alice", "911", 1, agent2, null);

        send(post(inbox() + "/chats/" + c.getId() + "/unassign"), agentToken, null).andExpect(status().isForbidden());
        send(post(inbox() + "/chats/" + c.getId() + "/unassign"), managerToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedUser").doesNotExist());
    }

    @Test
    void closeClearsAssignmentAndReopenKeepsNothingToRestore() throws Exception {
        WaWebChat c = chat("Alice", "911", 1, agent, null);

        send(post(inbox() + "/chats/" + c.getId() + "/close"), agentToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.assignedUser").doesNotExist());
        WaWebChat stored = chats.findById(c.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ChatStatus.CLOSED);
        assertThat(stored.getAssignedUserId()).isNull();

        // once unassigned the agent no longer sees it; the manager reopens
        send(post(inbox() + "/chats/" + c.getId() + "/reopen"), agentToken, null).andExpect(status().isNotFound());
        send(post(inbox() + "/chats/" + c.getId() + "/reopen"), managerToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void settingsRoundTrip() throws Exception {
        String url = inbox() + "/phone-numbers/" + number.getId() + "/settings";

        send(get(url), managerToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("NONE"))
                .andExpect(jsonPath("$.claimOnReply").value(true))
                .andExpect(jsonPath("$.keepAssignmentOnClose").value(false));

        send(put(url), managerToken, Map.of("mode", "TEAM", "teamId", support.getId(), "claimOnReply", false,
                "keepAssignmentOnClose", true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("TEAM"))
                .andExpect(jsonPath("$.teamId").value(support.getId()));

        send(get(url), managerToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("TEAM"))
                .andExpect(jsonPath("$.teamId").value(support.getId()))
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.claimOnReply").value(false))
                .andExpect(jsonPath("$.keepAssignmentOnClose").value(true));

        send(put(url), managerToken, Map.of("mode", "USER", "userId", agent.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("USER"))
                .andExpect(jsonPath("$.userId").value(agent.getId()))
                .andExpect(jsonPath("$.claimOnReply").value(false));

        send(put(url), managerToken, Map.of("teamId", support.getId())).andExpect(status().isBadRequest());
        send(put(url), leadToken, Map.of("mode", "NONE")).andExpect(status().isForbidden());
        send(get(url), leadToken, null).andExpect(status().isForbidden());
    }

    @Test
    void assigneesListsEnabledUsersAndTeams() throws Exception {
        User gone = brandUser("gone", BuiltInRoles.AGENT);
        gone.setActive(false);
        users.save(gone);
        User blocked = brandUser("blocked", BuiltInRoles.AGENT);
        blocked.setBrandDisables(List.of(new BrandDisable(brand.getId(), new Disabled(manager.getId(), EnableLevel.BRAND, T0))));
        users.save(blocked);
        team("Sales");

        JsonNode body = json(send(get(inbox() + "/assignees"), leadToken, null).andExpect(status().isOk()));

        List<String> users = new java.util.ArrayList<>();
        body.get("users").forEach(u -> users.add(u.get("username").asString()));
        assertThat(users).containsExactlyInAnyOrder("manager", "lead", "agent", "agent2");
        assertThat(body.get("users").get(0).get("name").asString()).startsWith("Test ");
        List<String> teamNames = new java.util.ArrayList<>();
        body.get("teams").forEach(t -> teamNames.add(t.get("name").asString()));
        assertThat(teamNames).containsExactly("Sales", "Support");
        assertThat(body.get("teams").get(1).get("id").asString()).isEqualTo(support.getId());

        send(get(inbox() + "/assignees"), agentToken, null).andExpect(status().isForbidden());
    }

    @Test
    void numbersListNeedsOnlyInboxView() throws Exception {
        send(get(inbox() + "/phone-numbers"), agentToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(number.getId()))
                .andExpect(jsonPath("$[0].number").value(number.getNumber()));
        send(get(inbox() + "/phone-numbers"), null, null).andExpect(status().isUnauthorized());
    }

    @Test
    void streamIsEventStream() throws Exception {
        MvcResult result = mvc.perform(get(inbox() + "/phone-numbers/" + number.getId() + "/stream")
                        .header("Authorization", "Bearer " + agentToken).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted()).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentType()).startsWith("text/event-stream");
    }
}
