package me.nageshk.omnichannel.crm.report;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.platform.CampaignDirectory;
import me.nageshk.omnichannel.crm.platform.PlatformCampaign;
import me.nageshk.omnichannel.crm.report.dto.CampaignsView;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Spec C13. The host's campaigns started in the period (through the port, newest first, at most {@value #LIMIT}),
 * each with how many of its recipients are CRM contacts the caller may see. A restricted caller sees only campaigns
 * that reached at least one of their contacts.
 */
@Component
public class CampaignsReport {

    static final int LIMIT = 200;

    private final CampaignDirectory campaigns;
    private final MongoTemplate mongo;
    private final ReportScope scope;

    CampaignsReport(CampaignDirectory campaigns, MongoTemplate mongo, ReportScope scope) {
        this.campaigns = campaigns;
        this.mongo = mongo;
        this.scope = scope;
    }

    public CampaignsView build(CrmContext ctx, ResolvedFilter f, Function<String, String> numberLabels) {
        List<PlatformCampaign> found = ReportRunner.guard(
                () -> campaigns.campaigns(ctx.workspaceId(), f.from(), f.to(), LIMIT + 1));
        boolean truncated = found.size() > LIMIT;
        if (truncated) found = found.subList(0, LIMIT);
        // the platform contacts behind the CRM contacts the caller may see (the owner filter does not apply here)
        ResolvedFilter contacts = new ResolvedFilter(f.from(), f.to(), f.zone(), f.pipelineId(), f.phoneNumberIds(),
                Set.of());
        Query q = new Query(scope.contacts(ctx, contacts)).maxTime(ReportRunner.MAX_TIME);
        q.fields().include("contactId");
        Set<String> visible = new HashSet<>();
        for (Document d : ReportRunner.guard(() -> mongo.find(q, Document.class, "crmContacts"))) {
            String id = d.getString("contactId");
            if (id != null) visible.add(id);
        }
        List<CampaignsView.Row> rows = new ArrayList<>();
        for (PlatformCampaign c : found) {
            if (!f.phoneNumberIds().isEmpty() && c.phoneNumberIds().stream().noneMatch(f.phoneNumberIds()::contains)) {
                continue;
            }
            long reached = c.recipientContactIds().stream().filter(visible::contains).count();
            if (!ctx.pipelines().all() && reached == 0) continue;
            PlatformCampaign.Counts n = c.counts();
            rows.add(new CampaignsView.Row(c.id(), c.name(), c.status(), c.startedAt(),
                    c.phoneNumberIds().stream().map(id -> new CampaignsView.Number(id, numberLabels.apply(id))).toList(),
                    n.total(), n.sent(), n.delivered(), n.read(), n.replied(), n.failed(), reached, c.segmentId(),
                    c.segmentName()));
        }
        return new CampaignsView(rows, truncated);
    }
}
