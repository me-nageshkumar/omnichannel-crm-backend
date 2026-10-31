package me.nageshk.omnichannel.app.crm;

import java.util.Optional;
import me.nageshk.omnichannel.crm.access.CrmAccess;
import me.nageshk.omnichannel.crm.segment.AudienceMembers;
import me.nageshk.omnichannel.crm.segment.SegmentAudienceApi;
import me.nageshk.omnichannel.security.auth.AuthenticatedUser;
import me.nageshk.omnichannel.service.campaign.SegmentAudiences;
import org.springframework.stereotype.Component;

/** The host's campaigns reach the CRM's segments here, and only here (spec D C12). */
@Component
class SegmentAudiencesAdapter implements SegmentAudiences {

    private final SegmentAudienceApi api;
    private final CrmAccess access;

    SegmentAudiencesAdapter(SegmentAudienceApi api, CrmAccess access) {
        this.api = api;
        this.access = access;
    }

    @Override
    public Optional<SegmentRef> find(String workspaceId, String segmentId) {
        return api.find(workspaceId, segmentId).map(r -> new SegmentRef(r.id(), r.name()));
    }

    @Override
    public void requireAccess(String workspaceId, AuthenticatedUser actor) {
        access.resolve(actor, workspaceId).require("crm:segments");
    }

    @Override
    public AudienceMembers resolve(String workspaceId, String segmentId, AuthenticatedUser actor, String campaignId,
            String campaignName) {
        me.nageshk.omnichannel.crm.segment.AudienceMembers m = api.resolve(workspaceId, segmentId, actor, campaignId,
                campaignName);
        return new AudienceMembers(m.contactIds(), m.channelAccountByContactId(), m.total(), m.people(), m.truncated());
    }
}
