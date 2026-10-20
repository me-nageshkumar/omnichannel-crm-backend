package me.nageshk.omnichannel.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import me.nageshk.omnichannel.inbox.InboxScope.Level;
import me.nageshk.omnichannel.inbox.InboxScope.ListScope;
import org.bson.Document;
import org.junit.jupiter.api.Test;

class InboxScopeTest {

    private static WaWebChat chat(String userId, String teamId) {
        WaWebChat c = new WaWebChat();
        c.setAssignedUserId(userId);
        c.setAssignedTeamId(teamId);
        return c;
    }

    private final InboxScope mine = new InboxScope(Level.MINE, "u1", Set.of());
    private final InboxScope team = new InboxScope(Level.TEAM, "u1", Set.of("t1"));
    private final InboxScope all = new InboxScope(Level.ALL, "u1", Set.of("t1", "t2"));

    @Test
    void mineCriteriaMatchesOnlyAssignedUser() {
        assertThat(mine.criteria().getCriteriaObject()).isEqualTo(new Document("assignedUserId", "u1"));
        assertThat(mine.covers(chat("u1", null))).isTrue();
        assertThat(mine.covers(chat("u2", null))).isFalse();
        assertThat(mine.covers(chat(null, null))).isFalse();
        assertThat(mine.covers(chat(null, "t1"))).isFalse();
    }

    @Test
    void teamCriteriaCoversOwnTeamsAndQueue() {
        assertThat(team.covers(chat(null, "t1"))).isTrue();
        assertThat(team.covers(chat(null, null))).isTrue();
        assertThat(team.covers(chat("u1", "t9"))).isTrue();
        assertThat(team.covers(chat("u2", "t9"))).isFalse();
        assertThat(team.covers(chat("u2", "t1"))).isTrue();
        assertThat(team.covers(chat(null, "t9"))).isFalse();
    }

    @Test
    void teamCriteriaIsAnOrOfUserTeamAndQueue() {
        Document expected = new Document("$or", java.util.List.of(
                new Document("assignedUserId", "u1"),
                new Document("assignedTeamId", new Document("$in", java.util.List.of("t1"))),
                new Document("$and", java.util.List.of(
                        new Document("assignedUserId", (Object) null),
                        new Document("assignedTeamId", (Object) null)))));
        assertThat(team.criteria().getCriteriaObject()).isEqualTo(expected);
    }

    @Test
    void allAllowsEverything() {
        assertThat(all.criteria().getCriteriaObject()).isEqualTo(new Document());
        assertThat(all.covers(chat("u2", "t9"))).isTrue();
        for (ListScope s : ListScope.values()) assertThat(all.allows(s)).isTrue();
    }

    @Test
    void agentMayNotRequestTeamScope() {
        assertThat(mine.allows(ListScope.MINE)).isTrue();
        assertThat(mine.allows(ListScope.TEAM)).isFalse();
        assertThat(mine.allows(ListScope.UNASSIGNED)).isFalse();
        assertThat(mine.allows(ListScope.ALL)).isFalse();
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
        Document queue = new Document("$and", java.util.List.of(
                new Document("assignedUserId", (Object) null), new Document("assignedTeamId", (Object) null)));
        assertThat(team.criteriaFor(ListScope.MINE).getCriteriaObject()).isEqualTo(new Document("assignedUserId", "u1"));
        assertThat(team.criteriaFor(ListScope.UNASSIGNED).getCriteriaObject()).isEqualTo(queue);
        assertThat(team.criteriaFor(ListScope.TEAM).getCriteriaObject()).isEqualTo(new Document("$or", java.util.List.of(
                new Document("assignedTeamId", new Document("$in", java.util.List.of("t1"))), queue)));
        assertThat(all.criteriaFor(ListScope.ALL).getCriteriaObject()).isEqualTo(new Document());
    }

    @Test
    void allLevelTeamScopeUsesEveryWorkspaceTeam() {
        Document in = (Document) ((java.util.List<?>) all.criteriaFor(ListScope.TEAM).getCriteriaObject().get("$or")).stream()
                .map(Document.class::cast).filter(d -> d.containsKey("assignedTeamId")).findFirst().orElseThrow()
                .get("assignedTeamId");
        assertThat(Set.copyOf((java.util.Collection<?>) in.get("$in"))).isEqualTo(Set.of("t1", "t2"));
    }
}
