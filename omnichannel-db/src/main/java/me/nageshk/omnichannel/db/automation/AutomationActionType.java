package me.nageshk.omnichannel.db.automation;

public enum AutomationActionType {
    SEND_TEMPLATE, ASSIGN_CHAT, ADD_TO_CRM, START_CHATBOT,
    /** A Meta template on a Cloud API number (spec 2026-11-20 §5.6); {@code SEND_TEMPLATE} sends a saved message. */
    SEND_CLOUD_TEMPLATE
}
