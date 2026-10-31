package me.nageshk.omnichannel.app.crm;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import me.nageshk.omnichannel.crm.platform.CampaignDirectory;
import me.nageshk.omnichannel.crm.platform.CampaignRef;
import me.nageshk.omnichannel.db.campaign.CampaignStatus;
import me.nageshk.omnichannel.crm.platform.PlatformCampaign;
import me.nageshk.omnichannel.db.campaign.Campaign;
import me.nageshk.omnichannel.db.campaign.CampaignRecipient;
import me.nageshk.omnichannel.db.campaign.CampaignSender;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/** Campaigns of a brand for the CRM's campaigns card, read straight from the host's collections. */
@Component
class CampaignDirectoryAdapter implements CampaignDirectory {

    private final MongoTemplate mongo;

    CampaignDirectoryAdapter(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public List<PlatformCampaign> campaigns(String workspaceId, Instant from, Instant to, int limit) {
        List<Campaign> campaigns = mongo.find(new Query(Criteria.where("workspaceId").is(workspaceId)
                .and("startedAt").gte(from).lt(to)).with(Sort.by(Sort.Direction.DESC, "startedAt")).limit(limit)
                .maxTime(MAX_TIME), Campaign.class);
        if (campaigns.isEmpty()) return List.of();
        Set<String> senderIds = new HashSet<>();
        campaigns.forEach(c -> {
            if (c.getSenderIds() != null) senderIds.addAll(c.getSenderIds());
        });
        Map<String, String> numberOfSender = senderIds.isEmpty() ? Map.of()
                : mongo.find(new Query(Criteria.where("_id").in(senderIds).and("workspaceId").is(workspaceId))
                        .maxTime(MAX_TIME), CampaignSender.class).stream()
                        .collect(Collectors.toMap(CampaignSender::getId, CampaignSender::getPhoneNumberId, (a, b) -> a));
        List<PlatformCampaign> out = new ArrayList<>();
        for (Campaign c : campaigns) {
            List<String> numbers = (c.getSenderIds() == null ? List.<String>of() : c.getSenderIds()).stream()
                    .map(numberOfSender::get).filter(n -> n != null).distinct().toList();
            Set<String> contactIds = new HashSet<>(mongo.findDistinct(
                    new Query(Criteria.where("campaignId").is(c.getId()).and("contactId").ne(null)).maxTime(MAX_TIME),
                    "contactId", CampaignRecipient.class, String.class));
            Campaign.Counts n = c.getCounts() == null ? Campaign.Counts.ZERO : c.getCounts();
            out.add(new PlatformCampaign(c.getId(), c.getName(), c.getStatus() == null ? null : c.getStatus().name(),
                    c.getStartedAt(), numbers, new PlatformCampaign.Counts(n.total(), n.sent(), n.delivered(),
                            n.read(), n.replied(), n.failed()), contactIds, c.getAudience() == null ? null : c.getAudience().segmentId(),
                    c.getAudience() == null ? null : c.getAudience().segmentName()));
        }
        return out;
    }

    @Override
    public List<CampaignRef> usingSegment(String workspaceId, String segmentId) {
        return mongo.find(new Query(Criteria.where("workspaceId").is(workspaceId)
                .and("status").in(CampaignStatus.DRAFT, CampaignStatus.RUNNING, CampaignStatus.PAUSED)
                .and("audience.segmentId").is(segmentId)).with(Sort.by("nameKey")).maxTime(MAX_TIME), Campaign.class)
                .stream().map(c -> new CampaignRef(c.getId(), c.getName())).toList();
    }
}
