package me.nageshk.omnichannel.service.contact;

import java.util.List;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactFilterSpec;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Collation;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/** Pages through a brand's contacts for a filter. */
@Service
public class ContactSearchService {

    public static final int PAGE_SIZE = 50;

    public record Page(List<ContactView> items, long total, int page) {}

    private final MongoTemplate mongo;
    private final ContactQuery query;
    private final QueryTimeouts timeouts;

    public ContactSearchService(MongoTemplate mongo, ContactQuery query, QueryTimeouts timeouts) {
        this.mongo = mongo;
        this.query = query;
        this.timeouts = timeouts;
    }

    public Page search(String workspaceId, ContactFilterSpec filter, String filterId, ContactSort sort, Integer page) {
        int p = page == null || page < 0 ? 0 : page;
        Query q = new Query(query.forRequest(workspaceId, filter, filterId)).maxTime(timeouts.search());
        long total = QueryTimeouts.guarded(() -> mongo.count(q, Contact.class));
        ContactSort s = sort == null ? ContactSort.LAST_MESSAGE : sort;
        q.with(switch (s) {
            case LAST_MESSAGE -> Sort.by(Sort.Order.desc("lastMessageAt"), Sort.Order.asc("_id"));
            case NAME -> Sort.by(Sort.Order.asc("name"), Sort.Order.asc("_id"));
            case CREATED -> Sort.by(Sort.Order.desc("creationTime"), Sort.Order.asc("_id"));
        });
        if (s == ContactSort.NAME) q.collation(Collation.of("en").strength(Collation.ComparisonLevel.secondary()));
        q.skip((long) p * PAGE_SIZE).limit(PAGE_SIZE);
        List<ContactView> items = QueryTimeouts.guarded(() -> mongo.find(q, Contact.class)).stream().map(ContactView::of).toList();
        return new Page(items, total, p);
    }
}
