package me.nageshk.omnichannel.crm.segment;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import me.nageshk.omnichannel.crm.activity.ActivityLog;
import me.nageshk.omnichannel.crm.activity.ActivityType;
import me.nageshk.omnichannel.crm.activity.ActorType;
import me.nageshk.omnichannel.crm.common.CrmTarget;
import me.nageshk.omnichannel.crm.common.TargetType;
import me.nageshk.omnichannel.crm.contact.CrmContact;
import me.nageshk.omnichannel.crm.platform.ChannelKind;
import me.nageshk.omnichannel.crm.settings.CrmSettings;
import me.nageshk.omnichannel.crm.settings.CrmSettingsService;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Writes {@link ActivityType#CAMPAIGN_SENT} on every CRM contact behind a host contact a campaign messaged (spec D
 * C13): the system actor, the campaign and segment in the payload, the channel account stamped, and never a change
 * to the contact's own activity or any deal's rotting. Nothing is written for a brand whose CRM is off.
 */
@Component
public class CampaignActivityRecorder {

    private final MongoTemplate mongo;
    private final ActivityLog activities;
    private final CrmSettingsService settings;

    public CampaignActivityRecorder(MongoTemplate mongo, ActivityLog activities, CrmSettingsService settings) {
        this.mongo = mongo;
        this.activities = activities;
        this.settings = settings;
    }

    public void recordSent(String workspaceId, String campaignId, String campaignName, String segmentId,
            String segmentName, String contactId, String channelAccountId, ChannelKind kind, Instant at) {
        if (contactId == null || !settings.find(workspaceId).filter(CrmSettings::isEnabled).isPresent()) return;
        Query q = new Query(Criteria.where("workspaceId").is(workspaceId).and("contactId").is(contactId));
        q.fields().include("customerId");
        for (CrmContact c : mongo.find(q, CrmContact.class)) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("campaignId", campaignId);
            payload.put("campaignName", campaignName);
            payload.put("segmentId", segmentId);
            payload.put("segmentName", segmentName);
            payload.put("channelAccountId", channelAccountId);
            payload.put("channelKind", kind == null ? null : kind.name());
            activities.append(workspaceId, c.getCustomerId(), ActivityType.CAMPAIGN_SENT,
                    new CrmTarget(TargetType.CONTACT, c.getId()), null, channelAccountId, "system", ActorType.SYSTEM, at,
                    "Campaign \"" + campaignName + "\" sent", payload);
        }
    }
}
