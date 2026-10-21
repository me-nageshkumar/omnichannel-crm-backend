package me.nageshk.omnichannel.campaign;

import java.time.Clock;
import me.nageshk.omnichannel.api.DuplicateException;
import me.nageshk.omnichannel.api.NotFoundException;
import me.nageshk.omnichannel.auth.AuthenticatedUser;
import me.nageshk.omnichannel.tenant.Workspace;
import me.nageshk.omnichannel.tenant.WorkspaceRepository;
import me.nageshk.omnichannel.template.MessageTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/** Campaign drafts: create, edit and delete. */
@Service
public class CampaignService {

    /** An uploaded CSV audience; arrives with Task 5, until then always null. */
    public record CsvUpload(String fileName, byte[] bytes) {
    }

    private final CampaignRepository repository;
    private final CampaignValidator validator;
    private final WorkspaceRepository workspaces;
    private final MongoTemplate mongo;
    private final Clock clock;

    CampaignService(CampaignRepository repository, CampaignValidator validator, WorkspaceRepository workspaces,
            MongoTemplate mongo, Clock clock) {
        this.repository = repository;
        this.validator = validator;
        this.workspaces = workspaces;
        this.mongo = mongo;
        this.clock = clock;
    }

    public Campaign create(AuthenticatedUser me, String workspaceId, CampaignRequest req, CsvUpload csv) {
        CampaignValidator.Validated v = validator.validate(workspaceId, req, null, null);
        Campaign c = new Campaign();
        c.setWorkspaceId(workspaceId);
        c.setCustomerId(workspaces.findById(workspaceId).map(Workspace::getCustomerId).orElse(me.customerId()));
        c.setStatus(CampaignStatus.DRAFT);
        c.setCounts(Campaign.Counts.ZERO);
        c.setCreatedBy(me.getUsername());
        c.setCreationTime(clock.instant());
        apply(c, v);
        return save(c);
    }

    public Campaign update(String workspaceId, String id, CampaignRequest req, CsvUpload csv) {
        require(workspaceId, id);
        CampaignValidator.Validated v = validator.validate(workspaceId, req, null, id);
        Update set = new Update().set("name", v.name()).set("nameKey", CampaignValidator.nameKey(v.name()))
                .set("audience", v.audience())
                .set("templateIds", v.templates().stream().map(MessageTemplate::getId).toList())
                .set("templateNames", v.templates().stream().map(MessageTemplate::getName).toList())
                .set("placeholders", v.placeholders()).set("senderIds", v.senderIds())
                .set("lastUpdateTime", clock.instant()).inc("version", 1);
        long matched;
        try {
            matched = mongo.updateFirst(draft(workspaceId, id), set, Campaign.class).getMatchedCount();
        } catch (DuplicateKeyException raced) {
            throw new DuplicateException("name", "a campaign with this name already exists");
        }
        if (matched == 0) throw CampaignErrors.notEditable();
        return require(workspaceId, id);
    }

    public void delete(String workspaceId, String id) {
        require(workspaceId, id);
        if (mongo.remove(draft(workspaceId, id), Campaign.class).getDeletedCount() == 0) {
            throw CampaignErrors.notEditable();
        }
    }

    /** The campaign only while it is still a draft, so a start that wins the race is never overwritten. */
    private static Query draft(String workspaceId, String id) {
        return new Query(Criteria.where("_id").is(id).and("workspaceId").is(workspaceId)
                .and("status").is(CampaignStatus.DRAFT));
    }

    public Campaign require(String workspaceId, String id) {
        return repository.findByIdAndWorkspaceId(id, workspaceId)
                .orElseThrow(() -> new NotFoundException("campaign not found"));
    }

    private static void apply(Campaign c, CampaignValidator.Validated v) {
        c.setName(v.name());
        c.setNameKey(CampaignValidator.nameKey(v.name()));
        c.setAudience(v.audience());
        c.setTemplateIds(v.templates().stream().map(MessageTemplate::getId).toList());
        c.setTemplateNames(v.templates().stream().map(MessageTemplate::getName).toList());
        c.setPlaceholders(v.placeholders());
        c.setSenderIds(v.senderIds());
    }

    private Campaign save(Campaign c) {
        try {
            return repository.save(c);
        } catch (DuplicateKeyException raced) {
            throw new DuplicateException("name", "a campaign with this name already exists");
        }
    }
}
