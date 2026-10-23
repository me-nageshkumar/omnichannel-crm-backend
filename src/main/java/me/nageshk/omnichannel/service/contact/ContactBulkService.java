package me.nageshk.omnichannel.service.contact;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import me.nageshk.omnichannel.common.api.ForbiddenException;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.db.contact.Contact;
import me.nageshk.omnichannel.db.contact.ContactAttributeRepository;
import me.nageshk.omnichannel.db.contact.ContactFilterSpec;
import me.nageshk.omnichannel.db.contact.ContactLabelRepository;
import me.nageshk.omnichannel.db.contact.ContactNote;
import me.nageshk.omnichannel.security.access.AccessService;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/** Bulk label, attribute and delete actions on a selection of one brand's contacts, each one database operation. */
@Service
public class ContactBulkService {

    public static final int MAX_IDS = 1000;

    public enum Action { ADD_LABELS, REMOVE_LABELS, SET_ATTRIBUTE, CLEAR_ATTRIBUTE, DELETE }

    /** {@code ids} alone, or {@code filter} and/or {@code filterId}. */
    public record Selection(List<String> ids, ContactFilterSpec filter, String filterId) {}

    public record Request(Selection selection, Action action, List<String> labelIds, String attributeId, String value) {}

    public record Result(long matched, long changed) {}

    private volatile int deleteBatchSize = MAX_IDS;
    private final MongoTemplate mongo;
    private final ContactQuery query;
    private final ContactLabelRepository labels;
    private final ContactAttributeRepository attributes;
    private final AccessService access;
    private final QueryTimeouts timeouts;

    public ContactBulkService(MongoTemplate mongo, ContactQuery query, ContactLabelRepository labels,
            ContactAttributeRepository attributes, AccessService access, QueryTimeouts timeouts) {
        this.mongo = mongo;
        this.query = query;
        this.labels = labels;
        this.attributes = attributes;
        this.access = access;
        this.timeouts = timeouts;
    }

    public long count(String workspaceId, Selection selection) {
        Query q = new Query(criteria(workspaceId, selection)).maxTime(timeouts.count());
        return QueryTimeouts.guarded(() -> mongo.count(q, Contact.class));
    }

    public Result run(String workspaceId, Request req) {
        if (req == null || req.action() == null) throw new ValidationException("action", "is required");
        if (req.action() == Action.DELETE && !access.has(workspaceId, "contact:delete")) {
            throw new ForbiddenException("deleting contacts needs contact:delete");
        }
        Criteria criteria = criteria(workspaceId, req.selection());
        if (req.action() == Action.DELETE) return delete(workspaceId, criteria);
        Update update;
        Criteria needsChange; // only contacts the update would alter are written (and have their version bumped)
        switch (req.action()) {
            case ADD_LABELS -> {
                Object[] ids = brandLabels(workspaceId, req.labelIds()).toArray();
                update = new Update().addToSet("labels").each(ids);
                needsChange = Criteria.where("labels").not().all(ids);
            }
            case REMOVE_LABELS -> {
                Object[] ids = brandLabels(workspaceId, req.labelIds()).toArray();
                update = new Update().pullAll("labels", ids);
                needsChange = Criteria.where("labels").in(ids);
            }
            case SET_ATTRIBUTE -> {
                String key = attributeKey(workspaceId, req.attributeId());
                String v = value(req.value());
                update = new Update().set(key, v);
                needsChange = Criteria.where(key).ne(v);
            }
            default -> {
                String key = attributeKey(workspaceId, req.attributeId());
                update = new Update().unset(key);
                needsChange = Criteria.where(key).exists(true);
            }
        }
        Query matching = new Query(criteria).maxTime(timeouts.count());
        long matched = QueryTimeouts.guarded(() -> mongo.count(matching, Contact.class));
        long changed = matched == 0 ? 0
                : mongo.updateMulti(new Query(new Criteria().andOperator(criteria, needsChange)), update, Contact.class)
                        .getModifiedCount();
        return new Result(matched, changed);
    }

    /** Deletes in id-ordered batches that load only {@code _id}, so memory stays flat however many contacts match. */
    private Result delete(String workspaceId, Criteria criteria) {
        long matched = 0;
        long deleted = 0;
        String last = null;
        while (true) {
            Criteria page = last == null ? criteria
                    : new Criteria().andOperator(criteria, Criteria.where("_id").gt(last));
            Query q = new Query(page).with(Sort.by("_id")).limit(deleteBatchSize);
            q.fields().include("_id");
            List<String> ids = mongo.find(q, Contact.class).stream().map(Contact::getId).toList();
            if (ids.isEmpty()) break;
            matched += ids.size();
            deleted += mongo.remove(new Query(Criteria.where("workspaceId").is(workspaceId).and("_id").in(ids)),
                    Contact.class).getDeletedCount();
            mongo.remove(new Query(Criteria.where("workspaceId").is(workspaceId).and("contactId").in(ids)),
                    ContactNote.class);
            last = ids.get(ids.size() - 1);
            if (ids.size() < deleteBatchSize) break;
        }
        return new Result(matched, deleted);
    }

    /** Test hook: how many contacts one delete batch removes. */
    public void setDeleteBatchSize(int size) {
        this.deleteBatchSize = size;
    }

    private Criteria criteria(String workspaceId, Selection s) {
        String shape = "give ids, or filter and/or filterId";
        if (s == null) throw new ValidationException("selection", shape);
        boolean byQuery = s.filter() != null || s.filterId() != null;
        if ((s.ids() != null) == byQuery) throw new ValidationException("selection", shape);
        if (s.ids() != null) {
            if (s.ids().isEmpty()) throw new ValidationException("selection", "ids must not be empty");
            if (s.ids().size() > MAX_IDS) throw new ValidationException("selection", "at most " + MAX_IDS + " ids");
            return Criteria.where("workspaceId").is(workspaceId).and("_id").in(s.ids());
        }
        return query.forRequest(workspaceId, s.filter(), s.filterId());
    }

    private Set<String> brandLabels(String workspaceId, List<String> labelIds) {
        if (labelIds == null || labelIds.isEmpty()) throw new ValidationException("labelIds", "is required");
        Set<String> out = new LinkedHashSet<>(labelIds);
        Set<String> mine = new LinkedHashSet<>();
        labels.findAllById(out).stream().filter(l -> workspaceId.equals(l.getWorkspaceId())).forEach(l -> mine.add(l.getId()));
        if (!mine.containsAll(out)) throw new NotFoundException("label not found");
        return new LinkedHashSet<>(new ArrayList<>(out));
    }

    private String attributeKey(String workspaceId, String attributeId) {
        if (attributeId == null || attributeId.isBlank()) throw new ValidationException("attributeId", "is required");
        attributes.findByIdAndWorkspaceId(attributeId, workspaceId)
                .orElseThrow(() -> new NotFoundException("attribute not found"));
        return "attributes." + attributeId;
    }

    private static String value(String raw) {
        String v = raw == null ? "" : raw.trim();
        if (v.isEmpty() || v.length() > ContactService.MAX_ATTRIBUTE_VALUE) {
            throw new ValidationException("value", "must be 1 to " + ContactService.MAX_ATTRIBUTE_VALUE + " characters");
        }
        return v;
    }
}
