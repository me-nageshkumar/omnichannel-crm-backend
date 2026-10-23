package me.nageshk.omnichannel.crm.status;

import java.util.List;

/** What the console needs to know first: is the CRM on, limits, numbers, pipeline access, authorities. */
public record CrmStatusView(boolean enabled, Limits limits, List<Phone> phones, Object pipelineAccess,
        List<String> authorities, boolean attachmentsEnabled) {

    public record Limits(Integer maxPipelines, Integer maxCustomFields) {}

    public record Phone(String phoneNumberId, String number, String label, boolean canChat) {}
}
