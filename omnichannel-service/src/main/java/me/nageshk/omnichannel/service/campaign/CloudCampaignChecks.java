package me.nageshk.omnichannel.service.campaign;

import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.db.campaign.Campaign;
import me.nageshk.omnichannel.meta.api.CloudAccountInfo;
import me.nageshk.omnichannel.meta.api.CloudAccounts;
import me.nageshk.omnichannel.meta.api.CloudTemplates;
import org.springframework.stereotype.Component;

/**
 * What Meta's side may have changed under a Cloud campaign (spec 2026-11-21 §5.5): its template no longer sendable,
 * or one of its numbers rated red. The tick pauses a running campaign for these; resume refuses while they last.
 * Checked on each tick because the Meta module raises no events for them; a campaign pauses within one tick.
 */
@Component
class CloudCampaignChecks {

    /** Why the campaign must not send: the pause reason and the person's message. */
    record Problem(String reason, String message) {
    }

    static final String CLOUD_QUALITY_LOW = "CLOUD_QUALITY_LOW";

    private final CloudTemplates templates;
    private final CloudAccounts accounts;

    CloudCampaignChecks(CloudTemplates templates, CloudAccounts accounts) {
        this.templates = templates;
        this.accounts = accounts;
    }

    /** The first thing on Meta's side that stops the campaign, or null. */
    Problem problem(Campaign c) {
        if (c.getCloudTemplate() == null) return null;
        boolean sendable;
        try {
            sendable = templates.get(c.getWorkspaceId(), c.getCloudTemplate().cloudTemplateId()).sendable();
        } catch (NotFoundException gone) {
            sendable = false;
        }
        if (!sendable) {
            return new Problem(CloudCampaignChannel.TEMPLATE_UNAVAILABLE,
                    "The WhatsApp template is not approved, or Meta paused it.");
        }
        for (String id : c.getSenderIds()) {
            CloudAccountInfo a = accounts.find(id).orElse(null);
            if (a != null && "RED".equals(a.qualityRating())) {
                return new Problem(CLOUD_QUALITY_LOW, "A number's quality rating is red.");
            }
        }
        return null;
    }

    /** Before a person resumes: {@link #problem}, or a number that cannot send now. */
    Problem beforeResume(Campaign c) {
        Problem p = problem(c);
        if (p != null) return p;
        for (String id : c.getSenderIds()) {
            if (!accounts.find(id).map(CloudAccountInfo::canSend).orElse(false)) {
                return new Problem(null, "A number cannot send now; check it on Phone numbers.");
            }
        }
        return null;
    }
}
