package me.nageshk.omnichannel.service.automation;

/** What automations need from the CRM; implemented by the application, which knows both modules. */
public interface AutomationCrm {

    /** True when the contact is a CRM contact on the number; false when it is not or the brand's CRM is off. */
    boolean inCrm(String workspaceId, String contactId, String phoneNumberId);
}
