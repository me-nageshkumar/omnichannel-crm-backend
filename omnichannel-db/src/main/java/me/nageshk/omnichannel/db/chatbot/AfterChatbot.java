package me.nageshk.omnichannel.db.chatbot;

/** What happens to a chat a chatbot held when the chatbot ends without a hand-over (spec 2026-11-18 §5.3). */
public enum AfterChatbot {
    /** The chat is closed. */
    CLOSE,
    /** The chat is opened and routed to the number's team. */
    ROUTE
}
