package me.nageshk.omnichannel.service.inbox;

import lombok.RequiredArgsConstructor;
import me.nageshk.omnichannel.common.api.NoDeviceException;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.phone.PhoneNumber;
import me.nageshk.omnichannel.db.phone.PhoneNumberRepository;
import me.nageshk.omnichannel.security.access.AccessService;
import me.nageshk.omnichannel.service.ws.DeviceRegistry;
import me.nageshk.omnichannel.service.ws.DeviceSession;
import org.springframework.stereotype.Component;

/**
 * Finds the device to talk to for a chat. Reads the number through its repository, not {@code PhoneNumberService},
 * because that service calls {@link InboxCleanup} and so must not be reachable from here.
 */
@Component
@RequiredArgsConstructor
public class InboxDevices {

    private final PhoneNumberRepository numbers;
    private final AccessService access;
    private final DeviceRegistry registry;

    /**
     * @throws NotFoundException if the chat's number is not in the workspace
     * @throws NoDeviceException if the number is disabled or its device is missing or not CONNECTED
     */
    DeviceSession requireReady(String workspaceId, WaWebChat chat) {
        return requireReady(workspaceId, chat.getPhoneNumberId());
    }

    /** Same as {@link #requireReady(String, WaWebChat)}, for a number that has no chat yet. */
    DeviceSession requireReady(String workspaceId, String phoneNumberId) {
        PhoneNumber number = numbers.findByIdAndWorkspaceId(phoneNumberId, workspaceId)
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

    /** Whether {@link #requireReady(String, String)} would succeed. */
    public boolean isReady(String workspaceId, String phoneNumberId) {
        try {
            requireReady(workspaceId, phoneNumberId);
            return true;
        } catch (NoDeviceException | NotFoundException e) {
            return false;
        }
    }
}
