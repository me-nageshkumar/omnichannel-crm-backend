package me.nageshk.omnichannel.crm.company;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.crm.access.PipelineAccess;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * The derived figures of companies: linked contacts and open deal amount. Recomputed, never incremented, and written
 * by collection name so {@code version} is not bumped.
 */
@Component
public class CompanyCounters {

    public static final String COMPANIES = "crmCompanies";
    public static final String CONTACTS = "crmContacts";
    public static final String DEALS = "crmDeals";

    private final MongoTemplate mongo;

    CompanyCounters(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public void recompute(String workspaceId, String companyId) {
        if (companyId == null || companyId.isBlank()) return;
        long contacts = mongo.count(new Query(Criteria.where("workspaceId").is(workspaceId)
                .and("companyId").is(companyId)), CONTACTS);
        BigDecimal amount = openAmount(workspaceId, companyId, null);
        mongo.updateFirst(new Query(Criteria.where("_id").is(companyId).and("workspaceId").is(workspaceId)),
                new Update().set("contactCount", (int) contacts).set("openDealAmount", new Decimal128(amount)),
                COMPANIES);
    }

    /** Sum of the OPEN deals' amounts; {@code access} (when given) limits the deals to those pipelines. */
    BigDecimal openAmount(String workspaceId, String companyId, PipelineAccess access) {
        Criteria c = Criteria.where("workspaceId").is(workspaceId).and("companyId").is(companyId).and("status")
                .is("OPEN");
        Query q = new Query(access == null || access.all() ? c
                : new Criteria().andOperator(c, access.criteria("pipelineId")));
        q.fields().include("amount");
        BigDecimal sum = BigDecimal.ZERO;
        for (Document d : mongo.find(q, Document.class, DEALS)) {
            Object a = d.get("amount");
            if (a instanceof Decimal128 x) sum = sum.add(x.bigDecimalValue());
            else if (a instanceof Number n) sum = sum.add(new BigDecimal(n.toString()));
            else if (a instanceof String s && !s.isBlank()) sum = sum.add(new BigDecimal(s));
        }
        return sum;
    }

    /**
     * Sum of the OPEN deals' amounts per company over the pipelines of {@code access} (one aggregation grouped by
     * {@code companyId}); companies without such deals are absent. For restricted callers, whose view must not
     * reveal the stored brand-wide figure.
     */
    Map<String, BigDecimal> openAmounts(String workspaceId, Collection<String> companyIds, PipelineAccess access) {
        Map<String, BigDecimal> out = new HashMap<>();
        if (companyIds.isEmpty()) return out;
        Criteria c = new Criteria().andOperator(Criteria.where("workspaceId").is(workspaceId),
                Criteria.where("companyId").in(companyIds), Criteria.where("status").is("OPEN"),
                access.criteria("pipelineId"));
        for (Document g : mongo.getCollection(DEALS).aggregate(List.of(
                new Document("$match", c.getCriteriaObject()),
                new Document("$group", new Document("_id", "$companyId").append("sum",
                        new Document("$sum", new Document("$ifNull", List.of("$amount", 0)))))))) {
            Object sum = g.get("sum");
            out.put(String.valueOf(g.get("_id")), sum instanceof Decimal128 d ? d.bigDecimalValue()
                    : sum instanceof Number n ? new BigDecimal(n.toString()) : BigDecimal.ZERO);
        }
        return out;
    }
}
