package me.nageshk.omnichannel.campaign;

import java.time.Clock;
import me.nageshk.omnichannel.api.DuplicateException;
import me.nageshk.omnichannel.api.NotFoundException;
import me.nageshk.omnichannel.auth.AuthenticatedUser;
import me.nageshk.omnichannel.tenant.Workspace;
import me.nageshk.omnichannel.tenant.WorkspaceRepository;
import me.nageshk.omnichannel.template.MessageTemplate;
import org.springframework.dao.DuplicateKeyException;
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
    private final Clock clock;

    CampaignService(CampaignRepository repository, CampaignValidator validator, WorkspaceRepository workspaces,
            Clock clock) {
        this.repository = repository;
        this.validator = validator;
        this.workspaces = workspaces;
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
        Campaign c = require(workspaceId, id);
        if (c.getStatus() != CampaignStatus.DRAFT) throw CampaignErrors.notEditable();
        apply(c, validator.validate(workspaceId, req, null, id));
        return save(c);
    }

    public void delete(String workspaceId, String id) {
        Campaign c = require(workspaceId, id);
        if (c.getStatus() != CampaignStatus.DRAFT) throw CampaignErrors.notEditable();
        repository.delete(c);
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
