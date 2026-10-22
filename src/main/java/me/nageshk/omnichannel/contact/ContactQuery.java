package me.nageshk.omnichannel.contact;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import me.nageshk.omnichannel.api.NotFoundException;
import me.nageshk.omnichannel.api.ValidationException;
import me.nageshk.omnichannel.contact.advanced.StoredQueryCriteria;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;

/** Turns a structured {@link ContactFilterSpec} into a Mongo criteria that is always scoped to one brand. */
@Component
public class ContactQuery {

    private final ContactLabelRepository labels;
    private final ContactAttributeRepository attributes;
    private final ContactFilterRepository savedFilters;

    public ContactQuery(ContactLabelRepository labels, ContactAttributeRepository attributes,
            ContactFilterRepository savedFilters) {
        this.labels = labels;
        this.attributes = attributes;
        this.savedFilters = savedFilters;
    }

    /**
     * The brand's contacts matching an inline filter and/or a saved one (both apply when both are given).
     *
     * @throws NotFoundException if {@code filterId} is not a saved filter of this brand
     */
    public Criteria forRequest(String workspaceId, ContactFilterSpec inline, String filterId) {
        List<Criteria> parts = new ArrayList<>();
        if (filterId != null) {
            ContactFilter saved = savedFilters.findByIdAndWorkspaceId(filterId, workspaceId)
                    .orElseThrow(() -> new NotFoundException("filter not found"));
            if (FilterKind.orSimple(saved.getKind()) == FilterKind.ADVANCED) {
                parts.add(new StoredQueryCriteria(saved.getQueryJson()));
            } else {
                parts.addAll(parts(workspaceId, saved.getFilter()));
            }
        }
        parts.addAll(parts(workspaceId, inline));
        return scoped(workspaceId, parts);
    }

    /** All of the brand's contacts matching the filter. A null filter matches the whole brand. */
    public Criteria build(String workspaceId, ContactFilterSpec spec) {
        return scoped(workspaceId, parts(workspaceId, spec));
    }

    /** The brand's contacts matching a stored advanced query (one written by {@code AdvancedFilters}). */
    public Criteria advanced(String workspaceId, String queryJson) {
        return scoped(workspaceId, List.of(new StoredQueryCriteria(queryJson)));
    }

    /** {@code workspaceId = ws AND every part}: the one place a brand clause is added to a filter's conditions. */
    private static Criteria scoped(String workspaceId, List<Criteria> parts) {
        Criteria base = Criteria.where("workspaceId").is(workspaceId);
        return parts.isEmpty() ? base : base.andOperator(parts);
    }

    private List<Criteria> parts(String workspaceId, ContactFilterSpec f) {
        List<Criteria> parts = new ArrayList<>();
        if (f == null) return parts;
        text(f.q(), parts);
        labelParts(workspaceId, f, parts);
        attributeParts(workspaceId, f.attributes(), parts);
        if (f.source() != null && !f.source().isEmpty()) {
            parts.add(Criteria.where("source").in(f.source().stream().map(Enum::name).toList()));
        }
        if (f.numberHidden() != null) {
            parts.add(f.numberHidden() ? Criteria.where("phone").is(null) : Criteria.where("phone").ne(null));
        }
        if (f.doNotContact() != null) {
            parts.add(f.doNotContact() ? Criteria.where("doNotContact").ne(null) : Criteria.where("doNotContact").is(null));
        }
        range("creationTime", f.createdFrom(), f.createdTo(), parts);
        range("lastMessageAt", f.lastMessageFrom(), f.lastMessageTo(), parts);
        return parts;
    }

    private static void text(String q, List<Criteria> parts) {
        String t = q == null ? "" : q.trim();
        if (t.isEmpty()) return;
        List<Criteria> any = new ArrayList<>();
        any.add(Criteria.where("name").regex(escape(t), "i"));
        String digits = t.replaceAll("\\D", "");
        if (!digits.isEmpty()) any.add(Criteria.where("phone").regex(digits));
        parts.add(new Criteria().orOperator(any));
    }

    public static String escape(String s) {
        return s.replaceAll("[\\\\^$.|?*+()\\[\\]{}\\-/]", "\\\\$0");
    }

    /** Label ids of other brands are dropped: a positive label condition left with no ids matches nothing. */
    private void labelParts(String workspaceId, ContactFilterSpec f, List<Criteria> parts) {
        if (isEmpty(f.labelsAny()) && isEmpty(f.labelsAll()) && isEmpty(f.labelsNone())) return;
        Set<String> mine = labels.findByWorkspaceIdOrderByNameKey(workspaceId).stream().map(ContactLabel::getId)
                .collect(Collectors.toSet());
        if (!isEmpty(f.labelsAny())) {
            parts.add(Criteria.where("labels").in(f.labelsAny().stream().filter(mine::contains).toList()));
        }
        if (!isEmpty(f.labelsAll())) {
            List<String> ids = f.labelsAll().stream().filter(mine::contains).toList();
            parts.add(ids.isEmpty() ? Criteria.where("labels").in(List.of()) : Criteria.where("labels").all(ids));
        }
        if (!isEmpty(f.labelsNone())) {
            List<String> ids = f.labelsNone().stream().filter(mine::contains).toList();
            if (!ids.isEmpty()) parts.add(Criteria.where("labels").nin(ids));
        }
    }

    /** Conditions on attributes of other brands match nothing, except NOT_EXISTS which is dropped. */
    private void attributeParts(String workspaceId, List<ContactFilterSpec.AttributeFilter> conditions,
            List<Criteria> parts) {
        if (isEmpty(conditions)) return;
        Set<String> mine = attributes.findByWorkspaceIdOrderByNameKey(workspaceId).stream()
                .map(ContactAttribute::getId).collect(Collectors.toSet());
        for (ContactFilterSpec.AttributeFilter c : conditions) {
            if (c.attributeId() == null || c.op() == null) {
                throw new ValidationException("attributes", "each condition needs attributeId and op");
            }
            boolean needsValue = c.op() == ContactFilterSpec.Op.EQUALS || c.op() == ContactFilterSpec.Op.CONTAINS;
            if (needsValue && (c.value() == null || c.value().isEmpty())) {
                throw new ValidationException("attributes", c.op() + " needs a value");
            }
            if (!mine.contains(c.attributeId())) {
                if (c.op() != ContactFilterSpec.Op.NOT_EXISTS) parts.add(Criteria.where("_id").in(List.of()));
                continue;
            }
            String key = "attributes." + c.attributeId();
            parts.add(switch (c.op()) {
                case EQUALS -> Criteria.where(key).is(c.value());
                case CONTAINS -> Criteria.where(key).regex(escape(c.value()), "i");
                case EXISTS -> Criteria.where(key).exists(true);
                case NOT_EXISTS -> Criteria.where(key).exists(false);
            });
        }
    }

    private static void range(String field, Instant from, Instant to, List<Criteria> parts) {
        if (from == null && to == null) return;
        Criteria c = Criteria.where(field);
        if (from != null) c = c.gte(from);
        if (to != null) c = c.lte(to);
        parts.add(c);
    }

    private static boolean isEmpty(List<?> l) {
        return l == null || l.isEmpty();
    }
}
