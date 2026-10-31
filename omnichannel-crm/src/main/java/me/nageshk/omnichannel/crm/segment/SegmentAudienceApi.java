package me.nageshk.omnichannel.crm.segment;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.crm.access.CrmAccess;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * The CRM module's one door for the host's campaigns (spec D C12, C14): find a segment by id, and resolve it for
 * the person starting a campaign, stamping the segment with that campaign. Everything else about segments stays
 * behind the CRM's own API.
 */
@Component
public class SegmentAudienceApi {

    private final CrmSegmentRepository repository;
    private final SegmentResolver resolver;
    private final CrmAccess access;
    private final MongoTemplate mongo;
    private final Clock clock;

    public SegmentAudienceApi(CrmSegmentRepository repository, SegmentResolver resolver, CrmAccess access,
            MongoTemplate mongo, Clock clock) {
        this.repository = repository;
        this.resolver = resolver;
        this.access = access;
        this.mongo = mongo;
        this.clock = clock;
    }

    public Optional<SegmentRef> find(String workspaceId, String segmentId) {
        if (segmentId == null || !ObjectId.isValid(segmentId)) return Optional.empty();
        return repository.findByIdAndWorkspaceId(segmentId, workspaceId).map(s -> new SegmentRef(s.getId(), s.getName()));
    }

    /**
     * The members as {@code actor} may see them (402/403/404 as every CRM call; {@code crm:segments} required), and
     * the segment stamped as last sent by this campaign — a targeted write that leaves its version alone.
     */
    public AudienceMembers resolve(String workspaceId, String segmentId, AuthenticatedUser actor, String campaignId,
            String campaignName) {
        CrmContext ctx = access.resolve(actor, workspaceId);
        ctx.require(SegmentService.AUTHORITY);
        CrmSegment s = repository.findByIdAndWorkspaceId(segmentId, workspaceId)
                .orElseThrow(() -> new NotFoundException("Segment not found"));
        SegmentMembers m = resolver.resolve(ctx, s.getRules());
        List<String> contactIds = new ArrayList<>();
        Map<String, String> accounts = new LinkedHashMap<>();
        for (SegmentMember member : m.members()) {
            if (member.contactId() == null || accounts.containsKey(member.contactId())) continue;
            contactIds.add(member.contactId());
            accounts.put(member.contactId(), member.channelAccountId());
        }
        mongo.updateFirst(new Query(Criteria.where("_id").is(new ObjectId(s.getId())).and("workspaceId").is(workspaceId)),
                new Update().set("lastSentAt", Instant.now(clock)).set("lastCampaignId", campaignId)
                        .set("lastCampaignName", campaignName), "crmSegments");
        return new AudienceMembers(contactIds, accounts, m.total(), m.people(), m.truncated());
    }
}
