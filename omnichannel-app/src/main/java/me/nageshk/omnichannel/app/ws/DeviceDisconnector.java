package me.nageshk.omnichannel.app.ws;

import me.nageshk.omnichannel.security.phone.DeviceAccessChanged;
import me.nageshk.omnichannel.service.ws.DeviceRegistry;
import me.nageshk.omnichannel.service.ws.DeviceSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Closes live connections the moment their number, brand or customer may no longer connect. */
@Component
public class DeviceDisconnector {

    public static final int CLOSE_DISABLED = 4403;
    public static final int CLOSE_REVOKED = 4410;
    public static final String DISABLED_REASON = "This number, its brand or its company is disabled.";
    public static final String REVOKED_REASON = "This device was removed. Enter a new connect code.";

    private static final Logger log = LoggerFactory.getLogger(DeviceDisconnector.class);

    private final DeviceRegistry registry;

    public DeviceDisconnector(DeviceRegistry registry) {
        this.registry = registry;
    }

    @EventListener
    public void on(DeviceAccessChanged change) {
        for (DeviceSession d : registry.all()) {
            if (!matches(change, d)) continue;
            log.info("[{}] closing device: {}", d.phoneNumberId(), change.reason());
            switch (change.reason()) {
                case DISABLED -> d.close(CLOSE_DISABLED, DISABLED_REASON);
                case REVOKED, DELETED -> d.close(CLOSE_REVOKED, REVOKED_REASON);
                case REPLACED -> d.close(BridgeWebSocketHandler.CLOSE_REPLACED, "replaced by a new device");
            }
        }
    }

    private static boolean matches(DeviceAccessChanged c, DeviceSession d) {
        if (c.phoneNumberId() != null) return c.phoneNumberId().equals(d.phoneNumberId());
        if (c.workspaceId() != null) return c.workspaceId().equals(d.principal().workspaceId());
        return c.customerId() != null && c.customerId().equals(d.principal().customerId());
    }
}
