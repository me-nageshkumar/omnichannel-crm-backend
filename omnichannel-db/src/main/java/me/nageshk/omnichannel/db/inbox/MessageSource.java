package me.nageshk.omnichannel.db.inbox;

/** Where a message came from: the contact, our console, the phone / WhatsApp Web tab, a campaign, or an automation rule. */
public enum MessageSource {
    CONTACT, CONSOLE, WHATSAPP_WEB, CAMPAIGN, AUTOMATION, CHATBOT
}
