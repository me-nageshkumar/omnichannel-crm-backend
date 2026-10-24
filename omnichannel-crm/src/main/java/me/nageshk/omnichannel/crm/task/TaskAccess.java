package me.nageshk.omnichannel.crm.task;

import java.util.ArrayList;
import java.util.List;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.crm.access.CrmContext;
import me.nageshk.omnichannel.crm.common.CrmTarget;
import me.nageshk.omnichannel.crm.common.TargetType;
import me.nageshk.omnichannel.crm.company.CrmCompany;
import me.nageshk.omnichannel.crm.company.CrmCompanyService;
import me.nageshk.omnichannel.crm.contact.CrmContact;
import me.nageshk.omnichannel.crm.contact.CrmContactService;
import me.nageshk.omnichannel.crm.deal.CrmDeal;
import me.nageshk.omnichannel.crm.deal.DealService;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Who may see a task: whoever may see its target (deal: its pipeline; contact: a contact in the caller's pipelines;
 * company: everyone with the CRM). Ruling B2: contact tasks of restricted callers are matched by the ids of the
 * contacts they can see, read fresh on each query.
 */
@Component
public class TaskAccess {

    /** The target as a task needs it: display name, deal pipeline and channel. */
    public record TargetInfo(String name, String pipelineId, String phoneNumberId) {}

    private final DealService deals;
    private final CrmContactService contacts;
    private final CrmCompanyService companies;
    private final MongoTemplate mongo;

    TaskAccess(DealService deals, CrmContactService contacts, CrmCompanyService companies, MongoTemplate mongo) {
        this.deals = deals;
        this.contacts = contacts;
        this.companies = companies;
        this.mongo = mongo;
    }

    /** @throws me.nageshk.omnichannel.common.api.NotFoundException when the caller may not see the target */
    public TargetInfo assertVisible(CrmContext ctx, CrmTarget target) {
        if (target == null || target.type() == null || target.id() == null || target.id().isBlank())
            throw new ValidationException("target", "is required");
        return switch (target.type()) {
            case DEAL -> {
                CrmDeal d = deals.load(ctx, target.id());
                yield new TargetInfo(d.getTitle(), d.getPipelineId(),
                        d.getChannel() == null ? null : d.getChannel().phoneNumberId());
            }
            case CONTACT -> {
                CrmContact c = contacts.load(ctx, target.id());
                yield new TargetInfo(name(c), null, c.getChannel() == null ? null : c.getChannel().phoneNumberId());
            }
            case COMPANY -> {
                CrmCompany c = companies.load(ctx, target.id());
                yield new TargetInfo(c.getName(), null, null);
            }
            default -> throw new ValidationException("target.type", "must be CONTACT, COMPANY or DEAL");
        };
    }

    /** Matches the tasks the caller may see; empty criteria for {@code crm:view-all}. */
    public Criteria visibleCriteria(CrmContext ctx) {
        if (ctx.pipelines().all()) return new Criteria();
        List<Criteria> any = new ArrayList<>();
        any.add(Criteria.where("target.type").is(TargetType.COMPANY));
        if (!ctx.pipelines().pipelineIds().isEmpty()) {
            any.add(new Criteria().andOperator(Criteria.where("target.type").is(TargetType.DEAL),
                    ctx.pipelines().criteria("pipelineId")));
            Query visible = new Query(Criteria.where("workspaceId").is(ctx.workspaceId())
                    .andOperator(ctx.pipelines().criteria("pipelineIds")));
            visible.fields().include("_id");
            List<String> ids = mongo.find(visible, Document.class, "crmContacts").stream()
                    .map(d -> String.valueOf(d.get("_id"))).toList();
            if (!ids.isEmpty()) {
                any.add(new Criteria().andOperator(Criteria.where("target.type").is(TargetType.CONTACT),
                        Criteria.where("target.id").in(ids)));
            }
        }
        return new Criteria().orOperator(any);
    }

    static String name(CrmContact c) {
        String full = ((c.getFirstName() == null ? "" : c.getFirstName()) + " "
                + (c.getLastName() == null ? "" : c.getLastName())).trim();
        if (!full.isEmpty()) return full;
        if (c.getContactSnapshot() != null && c.getContactSnapshot().name() != null
                && !c.getContactSnapshot().name().isBlank()) return c.getContactSnapshot().name();
        return c.getPhone();
    }
}
