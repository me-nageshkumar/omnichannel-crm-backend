package me.nageshk.omnichannel.user;

import java.time.Instant;

/** Whether an agent is taking chats right now; set by the agent, read by routing (chat-assignment design §3.2). */
public record Availability(Status status, Instant at) {
    public enum Status { AVAILABLE, AWAY }
}
