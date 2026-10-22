package me.nageshk.omnichannel.campaign;

import java.util.List;
import me.nageshk.omnichannel.api.FilterInUseException;
import me.nageshk.omnichannel.contact.advanced.CampaignUses;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/** Finds the unfinished campaigns whose audience snapshot uses a label or attribute. */
@Component
class CampaignFieldUses implements CampaignUses {

    private final MongoTemplate mongo;

    CampaignFieldUses(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public List<FilterInUseException.Ref> using(String workspaceId, String usesField, String id) {
        Query q = new Query(Criteria.where("workspaceId").is(workspaceId)
                .and("status").in(CampaignStatus.DRAFT, CampaignStatus.RUNNING, CampaignStatus.PAUSED)
                .and("audience.uses." + usesField).is(id)).with(Sort.by("nameKey"));
        return mongo.find(q, Campaign.class).stream()
                .map(c -> new FilterInUseException.Ref(c.getId(), c.getName())).toList();
    }
}
