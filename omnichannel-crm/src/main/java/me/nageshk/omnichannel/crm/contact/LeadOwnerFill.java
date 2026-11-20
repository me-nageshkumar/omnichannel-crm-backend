package me.nageshk.omnichannel.crm.contact;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import me.nageshk.omnichannel.crm.access.CrmAccess;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.activity.ActivityLog;
import me.nageshk.omnichannel.crm.activity.ActivityType;
import me.nageshk.omnichannel.crm.activity.ActorType;
import me.nageshk.omnichannel.crm.common.CrmTarget;
import me.nageshk.omnichannel.crm.common.TargetType;
import me.nageshk.omnichannel.crm.deal.CrmDeal;
import me.nageshk.omnichannel.crm.deal.DealStatus;
import me.nageshk.omnichannel.crm.settings.CrmSettings;
import me.nageshk.omnichannel.crm.settings.CrmSettingsService;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Gives an <b>unowned</b> CRM lead the user a chat was handed to (spec 2026-11-20 §6.1): the CRM contact on the number
 * and its open, unarchived deals. Only an empty owner is filled, each record by one guarded update, so two racing
 * fills end with one owner and one activity per record. The contact is filled only when the user holds
 * {@code crm:view} in the brand; a deal only when their pipeline access allows its pipeline. Never throws for
 * "nothing to do".
 */
@Service
public class LeadOwnerFill {

    static final String SUMMARY = "Owner set by inbox routing";

    private final MongoTemplate mongo;
    private final CrmSettingsService settings;
    private final CrmAccess access;
    private final ActivityLog activities;
    private final Clock clock;

    LeadOwnerFill(MongoTemplate mongo, CrmSettingsService settings, CrmAccess access, ActivityLog activities,
            Clock clock) {
        this.mongo = mongo;
        this.settings = settings;
        this.access = access;
        this.activities = activities;
        this.clock = clock;
    }

    /** The records whose owner was set by this call; {@code crmContactId} null when the contact's owner was not set. */
    public record Filled(String crmContactId, List<String> dealIds) {
        static final Filled NOTHING = new Filled(null, List.of());
    }

    public Filled fill(String workspaceId, String customerId, String contactId, String phoneNumberId,
            String userId) {
        if (userId == null || settings.find(workspaceId).filter(CrmSettings::isEnabled).isEmpty()) {
            return Filled.NOTHING;
        }
        CrmContext me = access.resolveFor(access.forSystem(workspaceId, customerId), userId).orElse(null);
        if (me == null || !me.can("crm:view")) return Filled.NOTHING;

        Query ofContact = new Query(Criteria.where("workspaceId").is(workspaceId).and("contactId").is(contactId)
                .and("channel.phoneNumberId").is(phoneNumberId));
        CrmContact found = mongo.findOne(ofContact, CrmContact.class);
        if (found == null) return Filled.NOTHING;

        String filledContact = null;
        Instant now = Instant.now(clock);
        CrmContact saved = mongo.findAndModify(
                new Query(Criteria.where("_id").is(found.getId()).and("workspaceId").is(workspaceId)
                        .and("ownerId").is(null)),
                new Update().set("ownerId", userId).set("lastModifiedBy", "system").set("lastUpdateTime", now)
                        .inc("version", 1),
                FindAndModifyOptions.options().returnNew(true), CrmContact.class);
        if (saved != null) {
            filledContact = saved.getId();
            record(workspaceId, customerId, new CrmTarget(TargetType.CONTACT, saved.getId()), null,
                    saved.getChannel() == null ? phoneNumberId : saved.getChannel().phoneNumberId(), userId, now);
        }

        List<String> filledDeals = new ArrayList<>();
        List<CrmDeal> open = mongo.find(new Query(Criteria.where("workspaceId").is(workspaceId)
                .and("primaryCrmContactId").is(found.getId()).and("status").is(DealStatus.OPEN)
                .and("archived").is(false).and("ownerId").is(null)), CrmDeal.class);
        for (CrmDeal d : open) {
            if (!me.pipelines().allows(d.getPipelineId())) continue;
            CrmDeal dealSaved = mongo.findAndModify(
                    new Query(Criteria.where("_id").is(d.getId()).and("workspaceId").is(workspaceId)
                            .and("status").is(DealStatus.OPEN).and("archived").is(false).and("ownerId").is(null)),
                    new Update().set("ownerId", userId).set("lastModifiedBy", "system")
                            .set("lastUpdateTime", now).inc("version", 1),
                    FindAndModifyOptions.options().returnNew(true), CrmDeal.class);
            if (dealSaved == null) continue;
            filledDeals.add(dealSaved.getId());
            record(workspaceId, customerId, new CrmTarget(TargetType.DEAL, dealSaved.getId()),
                    dealSaved.getPipelineId(),
                    dealSaved.getChannel() == null ? null : dealSaved.getChannel().phoneNumberId(), userId, now);
        }
        return new Filled(filledContact, filledDeals);
    }

    private void record(String workspaceId, String customerId, CrmTarget target, String pipelineId,
            String phoneNumberId, String userId, Instant at) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("field", "ownerId");
        change.put("from", null);
        change.put("to", userId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("changes", List.of(change));
        activities.append(workspaceId, customerId, ActivityType.FIELD_CHANGED, target, pipelineId, phoneNumberId,
                "system", ActorType.SYSTEM, at, SUMMARY, payload);
    }
}
