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

    private static final Set<String> OWNED = Set.of("p1");
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
        assertThat(mine.criteria().getCriteriaObject()).isEqualTo(new Document("assignedUserId", "u1"));
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
        assertThat(queue.criteria().getCriteriaObject()).isEqualTo(expected);
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
        assertThat(team.criteria().getCriteriaObject()).isEqualTo(expected);
    }

    @Test
    void allAllowsEverything() {
        assertThat(all.criteria().getCriteriaObject()).isEqualTo(new Document());
        assertThat(all.covers(chat("u2", "t9", "p9"))).isTrue();
        for (ListScope s : ListScope.values()) assertThat(all.allows(s)).isTrue();
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
        assertThat(team.criteriaFor(ListScope.MINE).getCriteriaObject()).isEqualTo(new Document("assignedUserId", "u1"));
        assertThat(team.criteriaFor(ListScope.UNASSIGNED).getCriteriaObject()).isEqualTo(unassigned);
        assertThat(queue.criteriaFor(ListScope.UNASSIGNED).getCriteriaObject()).isEqualTo(unassigned);
        assertThat(team.criteriaFor(ListScope.TEAM).getCriteriaObject()).isEqualTo(new Document("$or", List.of(
                new Document("assignedTeamId", new Document("$in", List.of("t1"))), unassignedOnOwned())));
        assertThat(all.criteriaFor(ListScope.ALL).getCriteriaObject()).isEqualTo(new Document());
    }

    @Test
    void allLevelTeamScopeUsesEveryWorkspaceTeam() {
        Document in = (Document) ((List<?>) all.criteriaFor(ListScope.TEAM).getCriteriaObject().get("$or")).stream()
                .map(Document.class::cast).filter(d -> d.containsKey("assignedTeamId")).findFirst().orElseThrow()
                .get("assignedTeamId");
        assertThat(Set.copyOf((java.util.Collection<?>) in.get("$in"))).isEqualTo(Set.of("t1", "t2"));
    }
}
