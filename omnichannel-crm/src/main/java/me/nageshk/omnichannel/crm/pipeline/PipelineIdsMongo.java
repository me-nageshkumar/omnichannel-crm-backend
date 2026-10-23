package me.nageshk.omnichannel.crm.pipeline;

import java.util.HashSet;
import java.util.Set;
import me.nageshk.omnichannel.crm.access.PipelineIds;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/** {@link PipelineIds} read from {@code crmPipelines}. */
@Component
class PipelineIdsMongo implements PipelineIds {

    private final MongoTemplate mongo;

    PipelineIdsMongo(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Set<String> existing(String workspaceId) {
        return ids(Criteria.where("workspaceId").is(workspaceId));
    }

    @Override
    public Set<String> active(String workspaceId) {
        return ids(Criteria.where("workspaceId").is(workspaceId).and("active").is(true));
    }

    private Set<String> ids(Criteria criteria) {
        Query q = new Query(criteria);
        q.fields().include("_id");
        Set<String> out = new HashSet<>();
        mongo.find(q, CrmPipeline.class).forEach(p -> out.add(p.getId()));
        return out;
    }
}
