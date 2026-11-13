package me.nageshk.omnichannel.db.chatbot;

/**
 * What a waiting run waits for (spec §5.3): a WhatsApp reply on a chat (key: the chat id), a submitted form (key: the
 * form token) or a timer (key: the resume time as ISO text). A later kind adds a value here, a key and an answer source.
 */
public enum TaskKind {
    WHATSAPP_REPLY, FORM, TIMER
}
