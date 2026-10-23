package me.nageshk.omnichannel.service.campaign;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import me.nageshk.omnichannel.db.campaign.AudienceKind;
import me.nageshk.omnichannel.db.campaign.Campaign;
import me.nageshk.omnichannel.db.campaign.CampaignCounter;
import me.nageshk.omnichannel.db.campaign.CampaignCsvStore;
import me.nageshk.omnichannel.db.campaign.CampaignRecipient;
import me.nageshk.omnichannel.db.campaign.CampaignStatus;
import me.nageshk.omnichannel.db.campaign.PausedBy;
import me.nageshk.omnichannel.db.campaign.RecipientStatus;
import me.nageshk.omnichannel.db.campaign.SkipReason;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/** Moves a campaign through its life: start, and later pause, resume and terminate. */
@Service
public class CampaignLifecycleService {

    private final CampaignService campaigns;
    private final CampaignValidator validator;
    private final CampaignSettingsService settings;
    private final CampaignMaterialiser materialiser;
    private final CampaignCounter counter;
    private final CampaignCsvStore csvStore;
    private final MongoTemplate mongo;
    private final Clock clock;
    private final Set<String> starting = ConcurrentHashMap.newKeySet();

    CampaignLifecycleService(CampaignService campaigns, CampaignValidator validator, CampaignSettingsService settings,
            CampaignMaterialiser materialiser, CampaignCounter counter, CampaignCsvStore csvStore, MongoTemplate mongo,
            Clock clock) {
        this.campaigns = campaigns;
        this.validator = validator;
        this.settings = settings;
        this.materialiser = materialiser;
        this.counter = counter;
        this.csvStore = csvStore;
        this.mongo = mongo;
        this.clock = clock;
    }

    /** Checks the draft again, writes its recipients and makes it RUNNING. */
    public Campaign start(String workspaceId, String id) {
        if (campaigns.require(workspaceId, id).getStatus() != CampaignStatus.DRAFT || !starting.add(id)) {
            throw CampaignErrors.state("started");
        }
        try {
            Campaign c = campaigns.require(workspaceId, id);
            if (c.getStatus() != CampaignStatus.DRAFT) throw CampaignErrors.state("started");
            Campaign.Audience a = c.getAudience();
            validator.validate(workspaceId, requestOf(c), a.kind() == AudienceKind.CSV ? a.csvHeaders() : null, id);
            Instant now = clock.instant();
            materialiser.materialise(c, settings.get(workspaceId), now);
            Query draft = new Query(Criteria.where("_id").is(id).and("workspaceId").is(workspaceId)
                    .and("status").is(CampaignStatus.DRAFT));
            Update running = new Update().set("status", CampaignStatus.RUNNING).set("startedAt", now)
                    .set("lastUpdateTime", now).inc("version", 1);
            if (mongo.updateFirst(draft, running, Campaign.class).getMatchedCount() == 0) {
                mongo.remove(new Query(Criteria.where("campaignId").is(id)), CampaignRecipient.class);
                throw CampaignErrors.state("started");
            }
            if (a.csvFileId() != null) {
                csvStore.delete(a.csvFileId());
                mongo.updateFirst(new Query(Criteria.where("_id").is(id)), new Update().set("audience.csvFileId", null),
                        Campaign.class);
            }
            counter.refresh(id);
            return campaigns.require(workspaceId, id);
        } finally {
            starting.remove(id);
        }
    }

    /** RUNNING to PAUSED by a user. */
    public Campaign pause(String workspaceId, String id) {
        return transition(workspaceId, id, "paused", List.of(CampaignStatus.RUNNING),
                new Update().set("status", CampaignStatus.PAUSED).set("pausedBy", PausedBy.USER)
                        .set("pauseReason", null));
    }

    /** PAUSED, by a user or the system, to RUNNING. */
    public Campaign resume(String workspaceId, String id) {
        return transition(workspaceId, id, "resumed", List.of(CampaignStatus.PAUSED),
                new Update().set("status", CampaignStatus.RUNNING).unset("pausedBy").unset("pauseReason"));
    }

    /** Ends a RUNNING or PAUSED campaign and cancels its PENDING recipients; SENDING ones finish. */
    public Campaign terminate(String workspaceId, String id) {
        Instant now = clock.instant();
        Campaign c = transition(workspaceId, id, "terminated", List.of(CampaignStatus.RUNNING, CampaignStatus.PAUSED),
                new Update().set("status", CampaignStatus.TERMINATED).set("finishedAt", now));
        mongo.updateMulti(new Query(Criteria.where("campaignId").is(id).and("status").is(RecipientStatus.PENDING)),
                new Update().set("status", RecipientStatus.SKIPPED).set("skipReason", SkipReason.CANCELLED),
                CampaignRecipient.class);
        counter.refresh(id);
        return campaigns.require(workspaceId, id);
    }

    private Campaign transition(String workspaceId, String id, String verb, List<CampaignStatus> from, Update change) {
        campaigns.require(workspaceId, id);
        Query q = new Query(Criteria.where("_id").is(id).and("workspaceId").is(workspaceId).and("status").in(from));
        if (mongo.updateFirst(q, change.set("lastUpdateTime", clock.instant()).inc("version", 1), Campaign.class)
                .getMatchedCount() == 0) {
            throw CampaignErrors.state(verb);
        }
        return campaigns.require(workspaceId, id);
    }

    private static CampaignRequest requestOf(Campaign c) {
        Campaign.Audience a = c.getAudience();
        return new CampaignRequest(c.getName(),
                new CampaignRequest.AudienceRequest(a.kind(), a.filterId(), a.phoneColumn(), a.nameColumn()),
                c.getTemplateIds(), c.getPlaceholders(), c.getSenderIds());
    }
}
