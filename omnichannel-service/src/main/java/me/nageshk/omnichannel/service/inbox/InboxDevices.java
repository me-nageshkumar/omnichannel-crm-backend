package me.nageshk.omnichannel.service.inbox;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import me.nageshk.omnichannel.common.api.NoDeviceException;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.db.inbox.WaWebChat;
import me.nageshk.omnichannel.db.phone.PhoneNumber;
import me.nageshk.omnichannel.db.phone.PhoneNumberRepository;
import me.nageshk.omnichannel.security.access.AccessService;
import me.nageshk.omnichannel.service.ws.DeviceRegistry;
import me.nageshk.omnichannel.service.ws.DeviceSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Finds the device to talk to for a chat. Reads the number through its repository, not {@code PhoneNumberService},
 * because that service calls {@link InboxCleanup} and so must not be reachable from here.
 */
@Component
@RequiredArgsConstructor
public class InboxDevices {

    private static final Logger log = LoggerFactory.getLogger(InboxDevices.class);

    private final PhoneNumberRepository numbers;
    private final AccessService access;
    private final DeviceRegistry registry;
    /** The digits of each number, without the {@code +}; a number never changes once onboarded. */
    private final Map<String, String> digits = new ConcurrentHashMap<>();

    /**
     * @throws NotFoundException if the chat's number is not in the workspace
     * @throws NoDeviceException if the number is disabled or its device is missing or not CONNECTED
     */
    DeviceSession requireReady(String workspaceId, WaWebChat chat) {
        return requireReady(workspaceId, chat.getPhoneNumberId());
    }

    /** Same as {@link #requireReady(String, WaWebChat)}, for a number that has no chat yet. */
    public DeviceSession requireReady(String workspaceId, String phoneNumberId) {
        PhoneNumber number = numbers.findByIdAndWorkspaceId(phoneNumberId, workspaceId)
                .orElseThrow(() -> new NotFoundException("phone number not found"));
        remember(number);
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

    /**
     * The number's digits without the {@code +}, as a webhook's {@code display_phone_number}; null when the number is
     * unknown or cannot be read now. A number seen by {@link #requireReady} is answered without a read.
     */
    public String digitsOf(String phoneNumberId) {
        if (phoneNumberId == null) return null;
        String known = digits.get(phoneNumberId);
        if (known != null) return known;
        try {
            return numbers.findById(phoneNumberId).map(this::remember).orElse(null);
        } catch (RuntimeException e) {
            log.warn("could not read number {}: {}", phoneNumberId, e.toString());
            return null;
        }
    }

    private String remember(PhoneNumber number) {
        if (number.getId() == null || number.getNumber() == null) return null;
        String d = number.getNumber().startsWith("+") ? number.getNumber().substring(1) : number.getNumber();
        digits.put(number.getId(), d);
        return d;
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
