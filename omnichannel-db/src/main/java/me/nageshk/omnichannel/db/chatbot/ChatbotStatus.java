package me.nageshk.omnichannel.db.chatbot;

/** A draft never runs on live events; a published chatbot cannot be edited (spec §4.1). */
public enum ChatbotStatus {
    DRAFT, PUBLISHED
}
