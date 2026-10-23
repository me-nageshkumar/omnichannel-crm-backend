package me.nageshk.omnichannel.crm.contact;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nageshk.omnichannel.crm.access.PipelineAccess;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * The derived deal figures of CRM contacts. Deals are read from the collection {@code crmDeals} by name; they are
 * recomputed from the deals, never incremented, and written without touching {@code version}.
 */
@Component
public class ContactCounters {

    static final String DEALS = "crmDeals";
    private static final String CONTACTS = "crmContacts";

    /** Open deals and their summed amount. */
    public record Totals(int count, BigDecimal amount) {
        static final Totals NONE = new Totals(0, BigDecimal.ZERO);
    }

    private final MongoTemplate mongo;

    ContactCounters(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /** Recomputes {@code openDealCount}, {@code openDealAmount} and {@code pipelineIds} from the contact's deals. */
    public void recompute(String workspaceId, String crmContactId) {
        int open = 0;
        BigDecimal amount = BigDecimal.ZERO;
        Set<String> pipelines = new LinkedHashSet<>();
        for (Document d : dealsOf(workspaceId, List.of(crmContactId), null)) {
            if (d.getString("pipelineId") != null) pipelines.add(d.getString("pipelineId"));
            if ("OPEN".equals(d.getString("status"))) {
                open++;
                amount = amount.add(amountOf(d));
            }
        }
        mongo.updateFirst(new Query(Criteria.where("_id").is(crmContactId).and("workspaceId").is(workspaceId)),
                new Update().set("openDealCount", open).set("openDealAmount", new Decimal128(amount))
                        .set("pipelineIds", new ArrayList<>(pipelines)),
                CONTACTS); // by name: an update through the entity class would bump the version
    }

    /** Open deal figures per contact over the deals the caller may see (restricted users' views). */
    Map<String, Totals> openTotals(String workspaceId, Collection<String> crmContactIds, PipelineAccess access) {
        Map<String, Totals> out = new HashMap<>();
        for (Document d : dealsOf(workspaceId, crmContactIds, access)) {
            if (!"OPEN".equals(d.getString("status"))) continue;
            Set<String> linked = new LinkedHashSet<>();
            if (d.getString("primaryCrmContactId") != null) linked.add(d.getString("primaryCrmContactId"));
            Object ids = d.get("crmContactIds");
            if (ids instanceof List<?> l) l.forEach(x -> linked.add(String.valueOf(x)));
            for (String id : linked) {
                if (!crmContactIds.contains(id)) continue;
                Totals t = out.getOrDefault(id, Totals.NONE);
                out.put(id, new Totals(t.count() + 1, t.amount().add(amountOf(d))));
            }
        }
        return out;
    }

    private List<Document> dealsOf(String workspaceId, Collection<String> ids, PipelineAccess access) {
        List<Criteria> all = new ArrayList<>();
        all.add(Criteria.where("workspaceId").is(workspaceId));
        all.add(new Criteria().orOperator(Criteria.where("primaryCrmContactId").in(ids),
                Criteria.where("crmContactIds").in(ids)));
        if (access != null && !access.all()) all.add(access.criteria("pipelineId"));
        Query q = new Query(new Criteria().andOperator(all));
        q.fields().include("status", "amount", "pipelineId", "primaryCrmContactId", "crmContactIds");
        return mongo.find(q, Document.class, DEALS);
    }

    private static BigDecimal amountOf(Document d) {
        Object a = d.get("amount");
        if (a instanceof Decimal128 x) return x.bigDecimalValue();
        if (a instanceof Number n) return new BigDecimal(n.toString());
        if (a instanceof String s && !s.isBlank()) return new BigDecimal(s);
        return BigDecimal.ZERO;
    }
}
