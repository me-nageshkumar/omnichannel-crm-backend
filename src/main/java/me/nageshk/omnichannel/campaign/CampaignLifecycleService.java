package me.nageshk.omnichannel.campaign;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    private static CampaignRequest requestOf(Campaign c) {
        Campaign.Audience a = c.getAudience();
        return new CampaignRequest(c.getName(),
                new CampaignRequest.AudienceRequest(a.kind(), a.filterId(), a.phoneColumn(), a.nameColumn()),
                c.getTemplateIds(), c.getPlaceholders(), c.getSenderIds());
    }
}
