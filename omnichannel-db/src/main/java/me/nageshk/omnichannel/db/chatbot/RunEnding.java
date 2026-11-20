package me.nageshk.omnichannel.db.chatbot;

import java.time.Instant;

/**
 * Where a run's chat went when the run ended (lead-owner report spec §6.2): handed over by the chatbot, routed or
 * closed by its settings, or left alone (jumped, replaced, or already held by a person).
 */
public record RunEnding(Chat chat, String agentUserId, String teamId, Instant at) {

    public enum Chat {
        HANDED_OVER, ROUTED, CLOSED, LEFT
    }
}
