package me.nageshk.omnichannel.db.phone;

/** How a number reaches WhatsApp: through a browser running WhatsApp Web today, the Cloud API later. */
public enum ConnectionType {
    WHATSAPP_WEB,
    WHATSAPP_CLOUD_API
}
