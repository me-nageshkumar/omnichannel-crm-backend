package me.nageshk.omnichannel.crm.access;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.common.api.ValidationException;
import me.nageshk.omnichannel.crm.activity.ActivityLog;
import me.nageshk.omnichannel.crm.activity.ActivityType;
import me.nageshk.omnichannel.crm.common.CrmTarget;
import me.nageshk.omnichannel.crm.common.TargetType;
import me.nageshk.omnichannel.crm.access.dto.UserPipelinesView;
import me.nageshk.omnichannel.crm.platform.PeopleDirectory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/** Which pipelines each user of a brand may work in. */
@Service
public class PipelineAccessService implements PipelineAccessReader {

    private final MongoTemplate mongo;
    private final PipelineIds pipelineIds;
    private final PeopleDirectory people;
    private final ActivityLog activities;

    PipelineAccessService(MongoTemplate mongo, PipelineIds pipelineIds, PeopleDirectory people,
            ActivityLog activities) {
        this.activities = activities;
        this.mongo = mongo;
        this.pipelineIds = pipelineIds;
        this.people = people;
    }

    @Override
    public Set<String> pipelineIdsOf(String workspaceId, String userId) {
        CrmPipelineAccess doc = find(workspaceId, userId);
        return doc == null ? Set.of() : new HashSet<>(doc.getPipelineIds());
    }

    /** Own assignment needs {@code crm:view}; another user's needs {@code crm:settings}. */
    public UserPipelinesView get(CrmContext ctx, String userId) {
        if (!userId.equals(ctx.userId())) ctx.require("crm:settings");
        requireBrandUser(ctx, userId);
        CrmPipelineAccess doc = find(ctx.workspaceId(), userId);
        return new UserPipelinesView(userId, doc == null ? List.of() : List.copyOf(doc.getPipelineIds()));
    }

    public UserPipelinesView put(CrmContext ctx, String userId, List<String> requested) {
        ctx.require("crm:settings");
        requireBrandUser(ctx, userId);
        if (requested == null) throw new ValidationException("pipelineIds", "is required");
        Set<String> wanted = new LinkedHashSet<>(requested);
        if (!pipelineIds.existing(ctx.workspaceId()).containsAll(wanted)) {
            throw new ValidationException("pipelineIds", "must all be pipelines of this brand");
        }
        List<String> ids = List.copyOf(wanted);
        Instant now = Instant.now();
        Update update = new Update().set("pipelineIds", ids).set("lastModifiedBy", ctx.userId())
                .set("lastUpdateTime", now).inc("version", 1)
                .setOnInsert("customerId", ctx.customerId()).setOnInsert("createdBy", ctx.userId())
                .setOnInsert("creationTime", now);
        try {
            upsert(ctx.workspaceId(), userId, update);
        } catch (DuplicateKeyException raced) {
            // two first assignments upserted at once and the other insert won: now it is a plain update
            upsert(ctx.workspaceId(), userId, update);
        }
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("userId", userId);
        payload.put("pipelineIds", ids);
        activities.record(ctx, ActivityType.SETTINGS_CHANGED, new CrmTarget(TargetType.SETTINGS, ctx.workspaceId()),
                List.of(), null, null, "Changed CRM pipelines of " + userId, payload);
        return new UserPipelinesView(userId, ids);
    }

    /** Drops a deleted pipeline from every user's assignment. */
    public void removePipeline(String workspaceId, String pipelineId) {
        mongo.updateMulti(new Query(Criteria.where("workspaceId").is(workspaceId).and("pipelineIds").is(pipelineId)),
                new Update().pull("pipelineIds", pipelineId), CrmPipelineAccess.class);
    }

    private void upsert(String workspaceId, String userId, Update update) {
        mongo.findAndModify(query(workspaceId, userId), update,
                FindAndModifyOptions.options().upsert(true).returnNew(true), CrmPipelineAccess.class);
    }

    private void requireBrandUser(CrmContext ctx, String userId) {
        if (!people.isBrandUser(ctx.workspaceId(), userId)) throw new NotFoundException("User not found");
    }

    private CrmPipelineAccess find(String workspaceId, String userId) {
        return mongo.findOne(query(workspaceId, userId), CrmPipelineAccess.class);
    }

    private static Query query(String workspaceId, String userId) {
        return new Query(Criteria.where("workspaceId").is(workspaceId).and("userId").is(userId));
    }
}
