package me.nageshk.omnichannel.contact;

import com.fasterxml.jackson.annotation.JsonRawValue;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.api.DuplicateException;
import me.nageshk.omnichannel.api.NotFoundException;
import me.nageshk.omnichannel.api.ValidationException;
import me.nageshk.omnichannel.contact.advanced.AdvancedFilters;
import me.nageshk.omnichannel.contact.advanced.RuleGroup;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/** A brand's saved contact filters. */
@Service
public class ContactFilterService {

    public static final int MAX_NAME = 60;
    public static final int SAMPLE_DEFAULT = 10;
    public static final int SAMPLE_MAX = 25;

    /** How many contacts an unsaved advanced filter matches, a few of them, and its compiled query (written raw). */
    public record Preview(long count, List<ContactView> sample, @JsonRawValue String query) {}

    private final ContactFilterRepository filters;
    private final ContactQuery query;
    private final AdvancedFilters advancedFilters;
    private final MongoTemplate mongo;
    private final QueryTimeouts timeouts;

    public ContactFilterService(ContactFilterRepository filters, ContactQuery query,
            AdvancedFilters advancedFilters, MongoTemplate mongo, QueryTimeouts timeouts) {
        this.filters = filters;
        this.query = query;
        this.advancedFilters = advancedFilters;
        this.mongo = mongo;
        this.timeouts = timeouts;
    }

    public List<FilterView> list(String workspaceId) {
        return filters.findByWorkspaceIdOrderByNameKey(workspaceId).stream().map(FilterView::of).toList();
    }

    public FilterView get(String workspaceId, String id) {
        return FilterView.of(require(workspaceId, id));
    }

    public FilterView create(String workspaceId, String username, String name, ContactFilterSpec filter,
            RuleGroup rules) {
        ContactFilter f = new ContactFilter();
        f.setWorkspaceId(workspaceId);
        f.setCreatedBy(username);
        return FilterView.of(save(f, name, filter, rules));
    }

    public FilterView update(String workspaceId, String id, String name, ContactFilterSpec filter, RuleGroup rules) {
        return FilterView.of(save(require(workspaceId, id), name, filter, rules));
    }

    /** Runs rules without saving them: count and sample each within the count time limit. */
    public Preview preview(String workspaceId, RuleGroup rules, Integer sample) {
        if (rules == null) throw new ValidationException("rules", "is required");
        AdvancedFilters.Compiled compiled = advancedFilters.compile(workspaceId, rules);
        Criteria criteria = query.advanced(workspaceId, compiled.queryJson());
        long count = QueryTimeouts.guarded(() -> mongo.count(new Query(criteria).maxTime(timeouts.count()),
                Contact.class));
        int limit = Math.clamp(sample == null ? SAMPLE_DEFAULT : sample, 1, SAMPLE_MAX);
        Query sampleQuery = new Query(criteria)
                .with(Sort.by(Sort.Order.desc("lastMessageAt"), Sort.Order.asc("_id")))
                .limit(limit)
                .maxTime(timeouts.count());
        List<ContactView> rows = QueryTimeouts.guarded(() -> mongo.find(sampleQuery, Contact.class)).stream()
                .map(ContactView::of).toList();
        return new Preview(count, rows, compiled.queryJson());
    }

    public void delete(String workspaceId, String id) {
        filters.delete(require(workspaceId, id));
    }

    private ContactFilter require(String workspaceId, String id) {
        return filters.findByIdAndWorkspaceId(id, workspaceId).orElseThrow(() -> new NotFoundException("filter not found"));
    }

    private ContactFilter save(ContactFilter f, String name, ContactFilterSpec filter, RuleGroup rules) {
        Map<String, String> errors = new LinkedHashMap<>();
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > MAX_NAME) errors.put("name", "must be 1 to " + MAX_NAME + " characters");
        if ((filter == null) == (rules == null)) errors.put("filter", "give exactly one of filter or rules");
        if (!errors.isEmpty()) throw new ValidationException(errors);
        boolean advanced = rules != null;
        if (f.getId() != null && advanced != (FilterKind.orSimple(f.getKind()) == FilterKind.ADVANCED))
            throw new ValidationException(advanced ? "rules" : "filter", "the kind of a saved filter cannot change");
        AdvancedFilters.Compiled compiled = null;
        if (advanced) compiled = advancedFilters.compile(f.getWorkspaceId(), rules);
        else query.build(f.getWorkspaceId(), filter); // rejects conditions that cannot run
        ContactFilter clash = filters.findByWorkspaceIdAndNameKey(f.getWorkspaceId(), n.toLowerCase()).orElse(null);
        if (clash != null && !clash.getId().equals(f.getId())) throw duplicate(n);
        f.setName(n);
        if (advanced) {
            f.setKind(FilterKind.ADVANCED);
            f.setFilter(null);
            f.setRules(rules);
            f.setQueryJson(compiled.queryJson());
            f.setQueryVersion(AdvancedFilters.QUERY_VERSION);
            f.setUses(compiled.uses());
        } else {
            f.setKind(FilterKind.SIMPLE);
            f.setFilter(filter);
            f.setRules(null);
            f.setQueryJson(null);
            f.setQueryVersion(null);
            f.setUses(null);
        }
        try {
            return filters.save(f);
        } catch (DuplicateKeyException e) {
            throw duplicate(n);
        }
    }

    private static DuplicateException duplicate(String name) {
        return new DuplicateException("name", "\"" + name + "\" already exists in this brand");
    }
}
