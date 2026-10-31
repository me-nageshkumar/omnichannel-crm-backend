package me.nageshk.omnichannel.crm.segment;

import me.nageshk.omnichannel.crm.contact.CrmContact;

/**
 * The one place the channel-neutral vocabulary of this feature meets the phase A names: a CRM contact's channel
 * account is, today, the WhatsApp number it landed on ({@code channel.phoneNumberId}). A later rename of the phase A
 * field changes this class only.
 */
public final class ChannelAccounts {

    /** The Mongo path of a CRM contact's or deal's channel account. */
    public static final String FIELD = "channel.phoneNumberId";

    private ChannelAccounts() {}

    public static String of(CrmContact c) {
        return c.getChannel() == null ? null : c.getChannel().phoneNumberId();
    }
}
