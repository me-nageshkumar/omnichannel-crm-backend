package me.nageshk.omnichannel.service.inbox;

/**
 * How the assignee passes a chat on (design §3.6): to a teammate ({@code TO_USER}), back to its team
 * ({@code TO_TEAM} without a team, or with the current one), or to another team ({@code TO_TEAM} with that team).
 */
public record HandoffRequest(Kind kind, String userId, String teamId) {
    public enum Kind { TO_USER, TO_TEAM }
}
