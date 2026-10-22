package me.nageshk.omnichannel.contact.advanced;

import org.bson.Document;
import org.springframework.data.mongodb.core.query.Criteria;

/**
 * A stored compiled query as a {@link Criteria}, parsed once. Use it only as a part of
 * {@link Criteria#andOperator}, which reads each part through {@link #getCriteriaObject()}.
 */
public final class StoredQueryCriteria extends Criteria {

    private final Document query;

    public StoredQueryCriteria(String queryJson) {
        this.query = AdvancedFilters.parse(queryJson);
    }

    @Override
    public Document getCriteriaObject() {
        return query;
    }
}
