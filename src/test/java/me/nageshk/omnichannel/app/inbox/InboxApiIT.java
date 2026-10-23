package me.nageshk.omnichannel.app.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import me.nageshk.omnichannel.common.enablement.EnableLevel;
import me.nageshk.omnichannel.db.enablement.Disabled;
import me.nageshk.omnichannel.db.inbox.ActionType;
import me.nageshk.omnichannel.db.inbox.ChatStatus;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.phone.PhoneNumber;
import me.nageshk.omnichannel.db.tenant.Team;
import me.nageshk.omnichannel.db.user.BrandDisable;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.security.access.BuiltInRoles;
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
        send(get(chatsUrl()).param("scope", "UNASSIGNED"), agentToken, null).andExpect(status().isOk());
        send(get(chatsUrl()).param("scope", "ALL"), agent2Token, null).andExpect(status().isForbidden());
        send(get(chatsUrl()).param("scope", "MINE"), agentToken, null).andExpect(status().isOk());
    }

    @Test
    void unknownScopeIsBadRequest() throws Exception {
        send(get(chatsUrl()).param("scope", "EVERYTHING"), managerToken, null).andExpect(status().isBadRequest());
    }

    @Test
    void managerTeamListCoversEveryTeamQueueAndUnassigned() throws Exception {
        chat("OnTeam", "911", 1, null, support);
        chat("Queue", "912", 2, null, null);
        chat("Own", "913", 3, manager2, null);
        chat("Theirs", "914", 4, agent2, null);
        Team sales = team("Sales", agent2);
        chat("OtherTeam", "915", 5, null, sales);

        JsonNode page = json(send(get(chatsUrl()).param("scope", "TEAM"), manager2Token, null).andExpect(status().isOk()));

        // a manager sees every team: the Team list is every team-routed chat plus the unassigned ones
        assertThat(names(page)).containsExactly("OtherTeam", "Queue", "OnTeam");
        assertThat(names(json(send(get(chatsUrl()).param("scope", "MINE"), manager2Token, null))))
                .containsExactly("Own");
        // Unassigned is everything nobody has taken yet: team queues included
        assertThat(names(json(send(get(chatsUrl()).param("scope", "UNASSIGNED"), manager2Token, null))))
                .containsExactly("OtherTeam", "Queue", "OnTeam");
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
        send(get(inbox() + "/chats").param("phoneNumberIds", otherNumber.getId()), managerToken, null)
                .andExpect(status().isNotFound());
        send(get(inbox() + "/stream").param("phoneNumberIds", otherNumber.getId()), managerToken, null)
                .andExpect(status().isNotFound());
        send(get(inbox() + "/assignees").param("phoneNumberId", otherNumber.getId()), managerToken, null)
                .andExpect(status().isNotFound());
        // the per-number list and stream are gone (the path still takes POST for starting a chat)
        send(get(inbox() + "/phone-numbers/" + number.getId() + "/chats"), managerToken, null)
                .andExpect(status().isMethodNotAllowed());
        send(get(inbox() + "/phone-numbers/" + number.getId() + "/stream"), managerToken, null)
                .andExpect(status().isNotFound());
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
    void searchTextIsCutAtOneHundredCharacters() throws Exception {
        chat("a".repeat(100) + "b", "914", 1);

        assertThat(names(json(send(get(chatsUrl()).param("q", "a".repeat(100) + "z".repeat(50)), managerToken, null))))
                .containsExactly("a".repeat(100) + "b");
        assertThat(names(json(send(get(chatsUrl()).param("q", "a".repeat(99) + "z"), managerToken, null)))).isEmpty();
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
        // an action with a name and no user (the fixture's "System", a campaign's reopen) shows the name alone
        assertThat(all.get("items").get(1).get("action").get("by").get("id").isNull()).isTrue();
        assertThat(all.get("items").get(1).get("action").get("by").get("name").asString()).isEqualTo("System");

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

        // the second manager can see it once it is in the team queue
        c.setAssignedUserId(null);
        c.setAssignedTeamId(support.getId());
        chats.save(c);
        send(post(url), manager2Token, Map.of("userId", manager2.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedUser.name").value("Test manager2"))
                .andExpect(jsonPath("$.assignedUser.id").value(manager2.getId()));
        assertThat(chats.findById(c.getId()).orElseThrow().getAssignedUserId()).isEqualTo(manager2.getId());

        send(post(url), manager2Token, Map.of()).andExpect(status().isBadRequest());
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

        // once unassigned it sits in the queue of the number's team, which the agent still sees; agent2 (no team) doesn't
        send(post(inbox() + "/chats/" + c.getId() + "/reopen"), agent2Token, null).andExpect(status().isNotFound());
        send(post(inbox() + "/chats/" + c.getId() + "/reopen"), agentToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void settingsRoundTrip() throws Exception {
        String url = inbox() + "/settings";
        Team sales = team("Sales", agent);

        send(get(url), managerToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.teamId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.inherited").value(false))
                .andExpect(jsonPath("$.assignToTeam").value(true))
                .andExpect(jsonPath("$.roundRobin").value(false))
                .andExpect(jsonPath("$.stickyDays").value(30))
                .andExpect(jsonPath("$.claimOnReply").value(true))
                .andExpect(jsonPath("$.keepAssignmentOnClose").value(false));

        send(put(url), managerToken, Map.of("assignToTeam", true, "roundRobin", true, "stickyDays", 7))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roundRobin").value(true))
                .andExpect(jsonPath("$.stickyDays").value(7));
        send(get(url), managerToken, null).andExpect(jsonPath("$.roundRobin").value(true));

        // a team without an override shows the default, flagged as inherited
        send(get(url + "/teams/" + sales.getId()), managerToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.teamId").value(sales.getId()))
                .andExpect(jsonPath("$.inherited").value(true))
                .andExpect(jsonPath("$.roundRobin").value(true));
        send(put(url + "/teams/" + sales.getId()), managerToken,
                Map.of("assignToTeam", true, "roundRobin", false, "userId", agent.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inherited").value(false))
                .andExpect(jsonPath("$.userId").value(agent.getId()))
                .andExpect(jsonPath("$.stickyDays").value(7));
        send(get(url + "/teams"), managerToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].teamId").value(sales.getId()))
                .andExpect(jsonPath("$[0].inherited").value(false))
                .andExpect(jsonPath("$[1].teamId").value(support.getId()))
                .andExpect(jsonPath("$[1].inherited").value(true));
        send(delete(url + "/teams/" + sales.getId()), managerToken, null).andExpect(status().isNoContent());
        send(get(url + "/teams/" + sales.getId()), managerToken, null).andExpect(jsonPath("$.inherited").value(true));

        send(put(url), managerToken, Map.of("assignToTeam", true, "roundRobin", false, "userId", agent.getId()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.userId").exists());
        send(put(url), managerToken, Map.of("assignToTeam", true, "roundRobin", true, "userId", agent.getId()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.routing").exists());
        send(put(url), agentToken, Map.of("assignToTeam", false)).andExpect(status().isForbidden());
        send(get(url), agentToken, null).andExpect(status().isForbidden());
        send(get(url + "/teams/nope"), managerToken, null).andExpect(status().isNotFound());
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

        JsonNode body = json(send(get(inbox() + "/assignees"), manager2Token, null).andExpect(status().isOk()));

        List<String> users = new java.util.ArrayList<>();
        body.get("users").forEach(u -> users.add(u.get("username").asString()));
        assertThat(users).containsExactlyInAnyOrder("manager", "manager2", "agent", "agent2");
        assertThat(body.get("users").get(0).get("name").asString()).startsWith("Test ");
        body.get("users").forEach(u -> assertThat(u.get("availability").asString()).isEqualTo("AVAILABLE"));
        List<String> teamNames = new java.util.ArrayList<>();
        body.get("teams").forEach(t -> teamNames.add(t.get("name").asString()));
        assertThat(teamNames).containsExactly("Sales", "Support");
        assertThat(body.get("teams").get(1).get("id").asString()).isEqualTo(support.getId());

        // agents may look too: the queue tab's claim menu needs the list
        send(get(inbox() + "/assignees"), agentToken, null).andExpect(status().isOk());
        send(get(inbox() + "/assignees"), null, null).andExpect(status().isUnauthorized());
    }

    @Test
    void assigneesForNumberListsOwningTeamAndAdminsWithAvailability() throws Exception {
        team("Sales", agent2);

        JsonNode body = json(send(get(inbox() + "/assignees").param("phoneNumberId", number.getId()), agentToken, null)
                .andExpect(status().isOk()));

        List<String> users = new java.util.ArrayList<>();
        body.get("users").forEach(u -> users.add(u.get("username").asString()));
        assertThat(users).containsExactlyInAnyOrder("manager", "manager2", "agent"); // agent2 cannot see this number
        body.get("users").forEach(u -> assertThat(u.get("availability").asString()).isEqualTo("AVAILABLE"));
        List<String> teamNames = new java.util.ArrayList<>();
        body.get("teams").forEach(t -> teamNames.add(t.get("name").asString()));
        assertThat(teamNames).containsExactly("Sales", "Support");
        send(get(inbox() + "/assignees").param("phoneNumberId", otherNumber.getId()), agentToken, null)
                .andExpect(status().isNotFound());
    }

    @Test
    void claimEndpointNeedsOnlyInboxSend() throws Exception {
        WaWebChat queued = chat("Queued", "911", 1, null, support);
        String url = inbox() + "/chats/" + queued.getId() + "/claim";

        send(post(url), agent2Token, null).andExpect(status().isNotFound()); // not their number, not their queue
        send(post(url), agentToken, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedUser.id").value(agent.getId()))
                .andExpect(jsonPath("$.assignedTeam.id").value(support.getId()))
                .andExpect(jsonPath("$.lastAgent.id").value(agent.getId()));
        send(post(url), manager2Token, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHAT_TAKEN"));
    }

    @Test
    void handoffEndpointShapes() throws Exception {
        WaWebChat mine = chat("Mine", "911", 1, agent, support);
        WaWebChat theirs = chat("Theirs", "912", 2, manager2, support);
        Team sales = team("Sales", agent2);

        send(post(inbox() + "/chats/" + mine.getId() + "/handoff"), agentToken,
                Map.of("kind", "TO_USER", "userId", manager2.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedUser.id").value(manager2.getId()));
        send(post(inbox() + "/chats/" + theirs.getId() + "/handoff"), agentToken,
                Map.of("kind", "TO_TEAM", "teamId", sales.getId())).andExpect(status().isNotFound());
        send(post(inbox() + "/chats/" + theirs.getId() + "/handoff"), manager2Token,
                Map.of("kind", "TO_TEAM", "teamId", sales.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedTeam.id").value(sales.getId()))
                .andExpect(jsonPath("$.assignedUser").doesNotExist());
        send(post(inbox() + "/chats/" + mine.getId() + "/handoff"), manager2Token, Map.of("kind", "SIDEWAYS"))
                .andExpect(status().isBadRequest());
        send(post(inbox() + "/chats/" + mine.getId() + "/handoff"), manager2Token, Map.of("kind", "TO_USER"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.userId").exists());
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
    void chatPagesNeverSplitATieGroup() throws Exception {
        chat("Old", "911", 1);
        chat("TieA", "912", 5);
        chat("TieB", "913", 5);
        chat("TieC", "914", 5);
        chat("New", "915", 9);

        JsonNode first = json(send(get(chatsUrl()).param("limit", "3"), managerToken, null));
        assertThat(names(first)).hasSize(4).contains("New", "TieA", "TieB", "TieC");
        assertThat(first.get("hasMore").asBoolean()).isTrue();

        String cursor = first.get("items").get(3).get("lastMessageAt").asString();
        JsonNode second = json(send(get(chatsUrl()).param("limit", "3").param("before", cursor), managerToken, null));
        assertThat(names(second)).containsExactly("Old");
        assertThat(second.get("hasMore").asBoolean()).isFalse();

        // the whole tie group is returned even when it overshoots the limit, and older chats remain
        JsonNode exact = json(send(get(chatsUrl()).param("limit", "2").param("status", "OPEN")
                .param("before", T0.plusSeconds(9 * 60).toString()), managerToken, null));
        assertThat(names(exact)).hasSize(3).doesNotContain("Old");
        assertThat(exact.get("hasMore").asBoolean()).isTrue();
    }

    @Test
    void threadPagesNeverSplitATieGroup() throws Exception {
        WaWebChat c = chat("Alice", "911", 1, agent, null);
        message(c, 10, "old");
        message(c, 20, "t1");
        message(c, 20, "t2");
        action(c, 20, ActionType.ASSIGNED);
        message(c, 30, "new");
        String url = inbox() + "/chats/" + c.getId() + "/thread";

        JsonNode first = json(send(get(url).param("limit", "2"), agentToken, null));
        assertThat(first.get("items")).hasSize(4);
        assertThat(first.get("hasMore").asBoolean()).isTrue();
        assertThat(first.get("items").get(0).get("at").asString()).isEqualTo(T0.plusSeconds(20).toString());
        assertThat(first.get("items").get(3).get("message").get("body").asString()).isEqualTo("new");

        JsonNode second = json(send(get(url).param("limit", "2")
                .param("before", first.get("items").get(0).get("at").asString()), agentToken, null));
        assertThat(second.get("items")).hasSize(1);
        assertThat(second.get("items").get(0).get("message").get("body").asString()).isEqualTo("old");
        assertThat(second.get("hasMore").asBoolean()).isFalse();

        // a tie group that exactly fills the limit: no extension needed, the older message remains
        JsonNode all = json(send(get(url).param("limit", "3").param("before", T0.plusSeconds(30).toString()),
                agentToken, null));
        assertThat(all.get("items")).hasSize(3);
        assertThat(all.get("hasMore").asBoolean()).isTrue();
    }

    @Test
    void streamIsEventStream() throws Exception {
        MvcResult result = mvc.perform(get(inbox() + "/stream").param("phoneNumberIds", number.getId())
                        .header("Authorization", "Bearer " + agentToken).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted()).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentType()).startsWith("text/event-stream");
    }

    // --- team-owned numbers, queue scope and the cross-number list (design §3.1, §3.5, §3.7) ---------------------

    private PhoneNumber ownedNumber(Team owner, String digits) {
        PhoneNumber n = testDevices.number(brand, digits + (System.nanoTime() % 1000000));
        n.setTeamId(owner == null ? null : owner.getId());
        return phoneNumbers.save(n);
    }

    private WaWebChat chatOn(PhoneNumber n, String name, String phone, long minutes, User assignedUser, Team team) {
        WaWebChat c = chat(n, brand, contact(brand, name, phone), minutes);
        c.setAssignedUserId(assignedUser == null ? null : assignedUser.getId());
        c.setAssignedTeamId(team == null ? null : team.getId());
        return chats.save(c);
    }

    private String list() {
        return inbox() + "/chats";
    }

    @Test
    void agentListsChatsAcrossOwnedNumbersNewestFirst() throws Exception {
        PhoneNumber b = ownedNumber(support, "9188");
        chat("OnA", "911", 1, agent, null);
        chatOn(b, "OnB", "912", 2, agent, null);
        chatOn(b, "Theirs", "913", 3, agent2, null);

        JsonNode page = json(send(get(list()), agentToken, null).andExpect(status().isOk()));

        assertThat(names(page)).containsExactly("OnB", "OnA");
        assertThat(page.get("items").get(0).get("phoneNumberId").asString()).isEqualTo(b.getId());
        assertThat(page.get("items").get(1).get("phoneNumberId").asString()).isEqualTo(number.getId());
    }

    @Test
    void phoneNumberFilterNarrowsToB() throws Exception {
        PhoneNumber b = ownedNumber(support, "9188");
        chat("OnA", "911", 1, agent, null);
        chatOn(b, "OnB", "912", 2, agent, null);

        JsonNode page = json(send(get(list()).param("phoneNumberIds", b.getId()), agentToken, null)
                .andExpect(status().isOk()));
        assertThat(names(page)).containsExactly("OnB");

        JsonNode both = json(send(get(list()).param("phoneNumberIds", b.getId() + "," + number.getId()), agentToken,
                null).andExpect(status().isOk()));
        assertThat(names(both)).containsExactly("OnB", "OnA");
    }

    @Test
    void numberOutsideTeamsIs404OnListAndChat() throws Exception {
        Team sales = team("Sales", agent2);
        PhoneNumber c = ownedNumber(sales, "9177");
        WaWebChat theirs = chatOn(c, "OnC", "913", 3, agent2, null);
        chatOn(c, "Queued", "914", 4, null, null);

        send(get(list()).param("phoneNumberIds", c.getId()), agentToken, null).andExpect(status().isNotFound());
        send(get(inbox() + "/chats/" + theirs.getId()), agentToken, null).andExpect(status().isNotFound());
        // nothing on C leaks into the unfiltered list either
        assertThat(names(json(send(get(list()).param("scope", "UNASSIGNED"), agentToken, null)))).isEmpty();
        send(get(list()).param("phoneNumberIds", c.getId()), managerToken, null).andExpect(status().isOk());
    }

    @Test
    void unassignedTabShowsQueueOfOwnTeam() throws Exception {
        Team sales = team("Sales", agent2);
        chat("SupportQueue", "911", 1, null, support);
        chat("Nobodys", "912", 2, null, null);
        chat("SalesQueue", "913", 3, null, sales);
        chat("Mine", "914", 4, agent, null);

        JsonNode page = json(send(get(list()).param("scope", "UNASSIGNED"), agentToken, null)
                .andExpect(status().isOk()));

        assertThat(names(page)).containsExactly("Nobodys", "SupportQueue");
        assertThat(names(json(send(get(list()), agentToken, null)))).containsExactly("Mine");
    }

    @Test
    void agentCannotSeeTeammatesAssignedChat() throws Exception {
        WaWebChat teammates = chat("Teammates", "911", 1, manager2, support);
        chat("Mine", "912", 2, agent, support);

        send(get(inbox() + "/chats/" + teammates.getId()), agentToken, null).andExpect(status().isNotFound());
        assertThat(names(json(send(get(list()), agentToken, null)))).containsExactly("Mine");
        assertThat(names(json(send(get(list()).param("scope", "UNASSIGNED"), agentToken, null)))).isEmpty();
        send(get(inbox() + "/chats/" + teammates.getId()), manager2Token, null).andExpect(status().isOk());
    }

    @Test
    void guestNumberShowsOnlyCoveredChats() throws Exception {
        Team sales = team("Sales", agent2);
        PhoneNumber c = ownedNumber(sales, "9177");
        WaWebChat handedToMe = chatOn(c, "HandedToMe", "913", 3, agent, null);
        chatOn(c, "SalesOwn", "914", 4, agent2, null);
        chatOn(c, "SalesQueue", "915", 5, null, sales);

        JsonNode numbers = json(send(get(inbox() + "/phone-numbers"), agentToken, null).andExpect(status().isOk()));
        assertThat(numbers).hasSize(2);
        JsonNode cRow = StreamSupport.stream(numbers.spliterator(), false)
                .filter(n -> n.get("id").asString().equals(c.getId())).findFirst().orElseThrow();
        assertThat(cRow.get("guest").asBoolean()).isTrue();
        assertThat(cRow.get("team").get("id").asString()).isEqualTo(sales.getId());
        JsonNode aRow = StreamSupport.stream(numbers.spliterator(), false)
                .filter(n -> n.get("id").asString().equals(number.getId())).findFirst().orElseThrow();
        assertThat(aRow.get("guest").asBoolean()).isFalse();

        JsonNode page = json(send(get(list()).param("phoneNumberIds", c.getId()), agentToken, null)
                .andExpect(status().isOk()));
        assertThat(names(page)).containsExactly("HandedToMe");
        send(get(inbox() + "/chats/" + handedToMe.getId()), agentToken, null).andExpect(status().isOk());
        // the guest number's own queue is not the agent's
        assertThat(names(json(send(get(list()).param("scope", "UNASSIGNED").param("phoneNumberIds", c.getId()),
                agentToken, null)))).isEmpty();
    }

    @Test
    void adminSeesUnownedNumber() throws Exception {
        PhoneNumber d = ownedNumber(null, "9166");

        JsonNode managers = json(send(get(inbox() + "/phone-numbers"), managerToken, null).andExpect(status().isOk()));
        assertThat(StreamSupport.stream(managers.spliterator(), false).map(n -> n.get("id").asString()))
                .containsExactlyInAnyOrder(number.getId(), d.getId());
        assertThat(StreamSupport.stream(managers.spliterator(), false).map(n -> n.get("guest").asBoolean()))
                .containsOnly(false);

        JsonNode agents = json(send(get(inbox() + "/phone-numbers"), agentToken, null).andExpect(status().isOk()));
        assertThat(StreamSupport.stream(agents.spliterator(), false).map(n -> n.get("id").asString()))
                .containsExactly(number.getId());
        // agent2 is in no team: nothing to see, and the list says so rather than failing
        assertThat(json(send(get(inbox() + "/phone-numbers"), agent2Token, null).andExpect(status().isOk()))).isEmpty();
        assertThat(names(json(send(get(list()), agent2Token, null).andExpect(status().isOk())))).isEmpty();
    }
}
