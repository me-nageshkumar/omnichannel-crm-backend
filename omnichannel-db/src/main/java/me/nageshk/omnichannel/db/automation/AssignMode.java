package me.nageshk.omnichannel.db.automation;

/** How a chat is assigned; {@code NUMBER_ROUTING} (the number's own routing) only on the chatbot hand-over node. */
public enum AssignMode {
    AGENT, TEAM_QUEUE, TEAM_ROUND_ROBIN, NUMBER_ROUTING
}
