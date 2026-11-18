package me.nageshk.omnichannel.service.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.service.inbox.InboxScope.Level;
import me.nageshk.omnichannel.service.inbox.InboxScope.ListScope;
import org.bson.Document;
import org.junit.jupiter.api.Test;

class InboxScopeTest {

    private static WaWebChat chat(String userId, String teamId, String phoneNumberId) {
        WaWebChat c = new WaWebChat();
        c.setAssignedUserId(userId);
        c.setAssignedTeamId(teamId);
        c.setPhoneNumberId(phoneNumberId);
        return c;
    }

    private static WaWebChat bot(String phoneNumberId) {
        WaWebChat c = chat(null, null, phoneNumberId);
        c.setStatus(me.nageshk.omnichannel.db.inbox.ChatStatus.BOT);
        return c;
    }

    /** People's lists never show a chat a chatbot holds (spec 2026-11-18 §5.6). */
    private static Document people(Document d) {
        return new Document("$and", List.of(d, NOT_BOT));
    }

    private static final Document NOT_BOT = new Document("status", new Document("$ne", "BOT"));
    private static final Document BOT_ON_OWNED = new Document("$and", List.of(new Document("status", "BOT"),
            new Document("phoneNumberId", new Document("$in", List.of("p1")))));

    private static final Set<String> OWNED = Set.of("p1");
    private final InboxScope queueWithBots = new InboxScope(Level.QUEUE, "u1", Set.of("t1"), OWNED, true);
    private final InboxScope mine = new InboxScope(Level.MINE, "u1", Set.of("t1"), OWNED);
    private final InboxScope queue = new InboxScope(Level.QUEUE, "u1", Set.of("t1"), OWNED);
    private final InboxScope team = new InboxScope(Level.TEAM, "u1", Set.of("t1"), OWNED);
    private final InboxScope all = new InboxScope(Level.ALL, "u1", Set.of("t1", "t2"), Set.of("p1", "p2"));

    private static Document unassignedOnOwned() {
        return new Document("$and", List.of(
                new Document("assignedUserId", (Object) null),
                new Document("assignedTeamId", (Object) null),
                new Document("phoneNumberId", new Document("$in", List.of("p1")))));
    }

    @Test
    void mineCriteriaMatchesOnlyAssignedUser() {
        assertThat(mine.criteria().getCriteriaObject()).isEqualTo(people(new Document("assignedUserId", "u1")));
        assertThat(mine.covers(chat("u1", null, "p1"))).isTrue();
        assertThat(mine.covers(chat("u2", null, "p1"))).isFalse();
        assertThat(mine.covers(chat(null, null, "p1"))).isFalse();
        assertThat(mine.covers(chat(null, "t1", "p1"))).isFalse();
    }

    @Test
    void queueLevelCoversOwnTeamQueueAndUnownedUnassignedOnOwnedNumbers() {
        assertThat(queue.covers(chat("u1", null, "p9"))).isTrue(); // own, wherever it is
        assertThat(queue.covers(chat(null, "t1", "p9"))).isTrue(); // own team's queue, even on a guest number
        assertThat(queue.covers(chat(null, null, "p1"))).isTrue(); // nobody's, on an owned number
        assertThat(queue.covers(chat(null, null, "p9"))).isFalse(); // nobody's, on someone else's number
        assertThat(queue.covers(chat(null, "t9", "p1"))).isFalse(); // another team's queue
    }

    @Test
    void queueLevelDoesNotCoverTeammatesAssignedChat() {
        assertThat(queue.covers(chat("u2", "t1", "p1"))).isFalse();
        assertThat(team.covers(chat("u2", "t1", "p1"))).isTrue();
    }

    @Test
    void queueCriteriaIsAnOrOfOwnTeamQueueAndOwnedUnassigned() {
        Document expected = new Document("$or", List.of(
                new Document("assignedUserId", "u1"),
                new Document("$and", List.of(
                        new Document("assignedUserId", (Object) null),
                        new Document("assignedTeamId", new Document("$in", List.of("t1"))))),
                unassignedOnOwned()));
        assertThat(queue.criteria().getCriteriaObject()).isEqualTo(people(expected));
    }

    @Test
    void teamCriteriaCoversOwnTeamsAndOwnedQueue() {
        assertThat(team.covers(chat(null, "t1", "p9"))).isTrue();
        assertThat(team.covers(chat(null, null, "p1"))).isTrue();
        assertThat(team.covers(chat(null, null, "p9"))).isFalse();
        assertThat(team.covers(chat("u1", "t9", "p9"))).isTrue();
        assertThat(team.covers(chat("u2", "t9", "p1"))).isFalse();
        assertThat(team.covers(chat("u2", "t1", "p1"))).isTrue();
        assertThat(team.covers(chat(null, "t9", "p1"))).isFalse();
    }

    @Test
    void teamCriteriaIsAnOrOfUserTeamAndOwnedQueue() {
        Document expected = new Document("$or", List.of(
                new Document("assignedUserId", "u1"),
                new Document("assignedTeamId", new Document("$in", List.of("t1"))),
                unassignedOnOwned()));
        assertThat(team.criteria().getCriteriaObject()).isEqualTo(people(expected));
    }

    @Test
    void allAllowsEverything() {
        assertThat(all.criteria().getCriteriaObject()).isEqualTo(NOT_BOT);
        assertThat(all.covers(chat("u2", "t9", "p9"))).isTrue();
        for (ListScope s : ListScope.values()) assertThat(all.allows(s)).isEqualTo(s != ListScope.BOT);
    }

    @Test
    void mineMayRequestOnlyMine() {
        assertThat(mine.allows(ListScope.MINE)).isTrue();
        assertThat(mine.allows(ListScope.TEAM)).isFalse();
        assertThat(mine.allows(ListScope.UNASSIGNED)).isFalse();
        assertThat(mine.allows(ListScope.ALL)).isFalse();
    }

    @Test
    void queueAllowsUnassignedListNotTeamList() {
        assertThat(queue.allows(ListScope.MINE)).isTrue();
        assertThat(queue.allows(ListScope.UNASSIGNED)).isTrue();
        assertThat(queue.allows(ListScope.TEAM)).isFalse();
        assertThat(queue.allows(ListScope.ALL)).isFalse();
    }

    @Test
    void teamLevelMayRequestTeamAndUnassignedButNotAll() {
        assertThat(team.allows(ListScope.MINE)).isTrue();
        assertThat(team.allows(ListScope.TEAM)).isTrue();
        assertThat(team.allows(ListScope.UNASSIGNED)).isTrue();
        assertThat(team.allows(ListScope.ALL)).isFalse();
    }

    @Test
    void listScopeCriteria() {
        Document unassigned = new Document("$and", List.of(
                new Document("assignedUserId", (Object) null),
                new Document("$or", List.of(
                        new Document("assignedTeamId", new Document("$in", List.of("t1"))),
                        new Document("$and", List.of(
                                new Document("assignedTeamId", (Object) null),
                                new Document("phoneNumberId", new Document("$in", List.of("p1")))))))));
        assertThat(team.criteriaFor(ListScope.MINE).getCriteriaObject()).isEqualTo(people(new Document("assignedUserId", "u1")));
        assertThat(team.criteriaFor(ListScope.UNASSIGNED).getCriteriaObject()).isEqualTo(people(unassigned));
        assertThat(queue.criteriaFor(ListScope.UNASSIGNED).getCriteriaObject()).isEqualTo(people(unassigned));
        assertThat(team.criteriaFor(ListScope.TEAM).getCriteriaObject()).isEqualTo(people(new Document("$or", List.of(
                new Document("assignedTeamId", new Document("$in", List.of("t1"))), unassignedOnOwned()))));
        assertThat(all.criteriaFor(ListScope.ALL).getCriteriaObject()).isEqualTo(NOT_BOT);
    }

    @Test
    void allLevelTeamScopeUsesEveryWorkspaceTeam() {
        Document team = (Document) ((List<?>) all.criteriaFor(ListScope.TEAM).getCriteriaObject().get("$and")).get(0);
        Document in = (Document) ((List<?>) team.get("$or")).stream()
                .map(Document.class::cast).filter(d -> d.containsKey("assignedTeamId")).findFirst().orElseThrow()
                .get("assignedTeamId");
        assertThat(Set.copyOf((java.util.Collection<?>) in.get("$in"))).isEqualTo(Set.of("t1", "t2"));
    }

    @Test
    void aBotChatIsCoveredOnlyWithThePermissionAndOnAnOwnedNumber() {
        assertThat(queue.covers(bot("p1"))).isFalse();
        assertThat(all.covers(bot("p1"))).isFalse();
        assertThat(queueWithBots.covers(bot("p1"))).isTrue();
        assertThat(queueWithBots.covers(bot("p9"))).isFalse();
        // the permission adds chatbot chats, nothing else
        assertThat(queueWithBots.covers(chat(null, null, "p9"))).isFalse();
    }

    @Test
    void theChatbotListNeedsThePermission() {
        assertThat(queue.allows(ListScope.BOT)).isFalse();
        assertThat(queueWithBots.allows(ListScope.BOT)).isTrue();
        assertThat(queueWithBots.criteriaFor(ListScope.BOT).getCriteriaObject()).isEqualTo(BOT_ON_OWNED);
    }

    @Test
    void criteriaWithThePermissionAddsChatbotChatsOnOwnedNumbers() {
        assertThat(queueWithBots.criteria().getCriteriaObject()).isEqualTo(new Document("$or", List.of(
                queue.criteria().getCriteriaObject(), BOT_ON_OWNED)));
    }
}
