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
 * Writes {@link ActivityType#MESSAGE_PAGE_OPENED} and {@link ActivityType#MESSAGE_PAGE_ACTION} on every CRM contact
 * behind a host contact who opened or acted on a message page: the system actor, the message and the channel account
 * in the payload, and never a change to the contact's own activity. Nothing is written for a brand whose CRM is off.
 */
@Component
public class MessagePageActivityRecorder {

    private final MongoTemplate mongo;
    private final ActivityLog activities;
    private final CrmSettingsService settings;

    public MessagePageActivityRecorder(MongoTemplate mongo, ActivityLog activities, CrmSettingsService settings) {
        this.mongo = mongo;
        this.activities = activities;
        this.settings = settings;
    }

    public void recordOpened(String workspaceId, String contactId, String channelAccountId, ChannelKind kind,
            String messageId, Instant at) {
        Map<String, Object> payload = base(messageId, channelAccountId, kind);
        write(workspaceId, contactId, channelAccountId, ActivityType.MESSAGE_PAGE_OPENED, at,
                "Opened the message page", payload);
    }

    public void recordAction(String workspaceId, String contactId, String channelAccountId, ChannelKind kind,
            String messageId, String action, String id, String title, Double latitude, Double longitude, Instant at) {
        Map<String, Object> payload = base(messageId, channelAccountId, kind);
        payload.put("action", action);
        payload.put("id", id);
        payload.put("title", title);
        payload.put("latitude", latitude);
        payload.put("longitude", longitude);
        String summary = switch (action == null ? "" : action) {
            case "CTA_URL" -> "Opened the link \"" + title + "\"";
            case "LOCATION" -> "Shared location";
            default -> "Tapped \"" + title + "\"";
        };
        write(workspaceId, contactId, channelAccountId, ActivityType.MESSAGE_PAGE_ACTION, at, summary, payload);
    }

    private static Map<String, Object> base(String messageId, String channelAccountId, ChannelKind kind) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageId", messageId);
        payload.put("channelAccountId", channelAccountId);
        payload.put("channelKind", kind == null ? null : kind.name());
        return payload;
    }

    private void write(String workspaceId, String contactId, String channelAccountId, ActivityType type, Instant at,
            String summary, Map<String, Object> payload) {
        if (contactId == null || !settings.find(workspaceId).filter(CrmSettings::isEnabled).isPresent()) return;
        Query q = new Query(Criteria.where("workspaceId").is(workspaceId).and("contactId").is(contactId));
        q.fields().include("customerId");
        for (CrmContact c : mongo.find(q, CrmContact.class)) {
            activities.append(workspaceId, c.getCustomerId(), type, new CrmTarget(TargetType.CONTACT, c.getId()), null,
                    channelAccountId, "system", ActorType.SYSTEM, at, summary, new LinkedHashMap<>(payload));
        }
    }
}
