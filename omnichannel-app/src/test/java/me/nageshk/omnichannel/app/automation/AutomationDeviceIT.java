package me.nageshk.omnichannel.app.automation;

import static org.assertj.core.api.Assertions.assertThat;

import me.nageshk.omnichannel.service.inbox.InboxDevices;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The fake device the automation and chatbot tests send through: when its constructor returns, the server already
 * counts the number as ready, so a first send never meets "WhatsApp Web is not ready" (the flaky
 * SendTemplateActionIT.whatsappRefusalFails of 2026-11-14, which got RETRYING under a busy parallel run).
 */
class AutomationDeviceIT extends AutomationTestBase {

    @Autowired InboxDevices devices;

    @Test
    void numberIsReadyWhenTheDeviceIsBuilt() throws Exception {
        for (int i = 0; i < 10; i++) {
            try (AutomationDevice d = new AutomationDevice(port, testDevices, n1)) {
                assertThat(devices.isReady(brand.getId(), n1.getId())).as("round %d", i).isTrue();
            }
        }
    }
}
