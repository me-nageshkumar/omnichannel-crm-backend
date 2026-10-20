package me.nageshk.omnichannel.inbox;

import lombok.RequiredArgsConstructor;
import me.nageshk.omnichannel.access.AccessService;
import me.nageshk.omnichannel.api.NoDeviceException;
import me.nageshk.omnichannel.api.NotFoundException;
import me.nageshk.omnichannel.phone.PhoneNumber;
import me.nageshk.omnichannel.phone.PhoneNumberRepository;
import me.nageshk.omnichannel.ws.DeviceRegistry;
import me.nageshk.omnichannel.ws.DeviceSession;
import org.springframework.stereotype.Component;

/**
 * Finds the device to talk to for a chat. Reads the number through its repository, not {@code PhoneNumberService},
 * because that service calls {@link InboxCleanup} and so must not be reachable from here.
 */
@Component
@RequiredArgsConstructor
class InboxDevices {

    private final PhoneNumberRepository numbers;
    private final AccessService access;
    private final DeviceRegistry registry;

    /**
     * @throws NotFoundException if the chat's number is not in the workspace
     * @throws NoDeviceException if the number is disabled or its device is missing or not CONNECTED
     */
    DeviceSession requireReady(String workspaceId, WaWebChat chat) {
        PhoneNumber number = numbers.findByIdAndWorkspaceId(chat.getPhoneNumberId(), workspaceId)
                .orElseThrow(() -> new NotFoundException("phone number not found"));
        if (number.getDisabled() != null || !access.isWorkspaceUsable(workspaceId)) {
            throw new NoDeviceException(NoDeviceException.NO_DEVICE, "the number is disabled");
        }
        DeviceSession device = registry.find(number.getId()).orElseThrow(() -> new NoDeviceException(
                NoDeviceException.NO_DEVICE, "no extension connected for number " + number.getId()));
        if (!device.confirmed() || !DeviceSession.STATE_CONNECTED.equals(device.waState())) {
            throw new NoDeviceException(NoDeviceException.WHATSAPP_NOT_READY, "WhatsApp state is " + device.waState());
        }
        return device;
    }
}
