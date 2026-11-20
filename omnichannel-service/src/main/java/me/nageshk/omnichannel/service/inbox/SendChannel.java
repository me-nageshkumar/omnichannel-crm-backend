package me.nageshk.omnichannel.service.inbox;

import me.nageshk.omnichannel.meta.api.CloudAccountInfo;
import me.nageshk.omnichannel.service.ws.DeviceSession;

/** How a send leaves: the number's WhatsApp Web device, or Meta's Cloud API (spec 2026-11-20 §5.4). */
public sealed interface SendChannel permits SendChannel.DeviceChannel, SendChannel.CloudChannel {

    record DeviceChannel(DeviceSession device) implements SendChannel {}

    record CloudChannel(CloudAccountInfo account) implements SendChannel {}
}
