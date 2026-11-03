package me.nageshk.omnichannel.app.crm;

import me.nageshk.omnichannel.crm.contact.CaptureService;
import me.nageshk.omnichannel.crm.settings.CrmSettings;
import me.nageshk.omnichannel.crm.settings.CrmSettingsService;
import me.nageshk.omnichannel.service.automation.AutomationCrm;
import org.springframework.stereotype.Component;

@Component
class AutomationCrmAdapter implements AutomationCrm {

    private final CrmSettingsService settings;
    private final CaptureService capture;

    AutomationCrmAdapter(CrmSettingsService settings, CaptureService capture) {
        this.settings = settings;
        this.capture = capture;
    }

    @Override
    public boolean inCrm(String workspaceId, String contactId, String phoneNumberId) {
        return settings.find(workspaceId).filter(CrmSettings::isEnabled).isPresent()
                && capture.inCrm(workspaceId, contactId, phoneNumberId);
    }
}
